package com.example.starter.orchestrator.engine;

import com.example.starter.orchestrator.domain.EventType;
import com.example.starter.orchestrator.domain.HealthStatus;
import com.example.starter.orchestrator.domain.NodeState;
import com.example.starter.orchestrator.domain.PlanStatus;
import com.example.starter.orchestrator.repo.ConditionRow;
import com.example.starter.orchestrator.repo.DependencyRow;
import com.example.starter.orchestrator.repo.EventRow;
import com.example.starter.orchestrator.repo.NodeRow;
import com.example.starter.orchestrator.repo.OrchestratorRepository;
import com.example.starter.orchestrator.repo.ParamRow;
import com.example.starter.orchestrator.repo.PlanRow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 计划执行引擎：按 DAG 逐波次启动节点、评估条件谓词、执行健康检查、
 * 失败传播阻断后继、逆依赖顺序补偿，以及取消与同 JVM 恢复。
 *
 * <p>执行由服务层提交到线程池异步运行，本引擎保证同一计划同时只有一个执行循环
 * （{@link #running} 守卫），因此状态迁移无需长事务加锁；取消通过 H2 中
 * {@code launch_plan.cancel_requested} 标志传递，执行循环每个波次及启动等待切片中观测。
 *
 * <p>运行时事实一律以 H2 中 plan_node/plan_event 为准，因此：
 * <ul>
 *   <li>已 SUCCESS 的节点恢复时不会重复启动；</li>
 *   <li>compensated=1 的节点恢复时不会重复补偿；</li>
 *   <li>补偿在执行循环单写者 + compensated 标记下保证只执行一次。</li>
 * </ul>
 */
@Component
public class PlanEngine {

    private static final Logger log = LoggerFactory.getLogger(PlanEngine.class);

    /** 取消等待期间每个切片的睡眠时间（毫秒），用于及时响应取消标志。 */
    private static final long DELAY_SLICE_MS = 20;

    private final OrchestratorRepository repo;
    private final Clock clock;

    /** 每个 planId 一把短锁，仅保护取消标志落库等短小临界区。 */
    private final ConcurrentHashMap<String, Object> planLocks = new ConcurrentHashMap<>();

    /** 同一计划的执行循环互斥守卫。 */
    private final ConcurrentHashMap<String, AtomicBoolean> running = new ConcurrentHashMap<>();

    public PlanEngine(OrchestratorRepository repo, Clock clock) {
        this.repo = repo;
        this.clock = clock;
    }

    /**
     * 执行（或恢复执行）一个计划。同一计划已有执行循环在运行时直接返回；
     * 已处于终态的计划不再执行。该方法通常由线程池异步调用。
     */
    public void executePlan(String planId) {
        AtomicBoolean guard = running.computeIfAbsent(planId, k -> new AtomicBoolean(false));
        if (!guard.compareAndSet(false, true)) {
            // 同一计划已有执行循环在运行
            return;
        }
        try {
            runPlan(planId);
        } catch (RuntimeException ex) {
            log.error("plan execution crashed: planId={}", planId, ex);
            throw ex;
        } finally {
            guard.set(false);
        }
    }

    /**
     * 请求取消计划（幂等）。
     *
     * @return true 表示本次新登记了取消请求；false 表示计划不存在或已处于终态（幂等命中）
     */
    public boolean cancelPlan(String planId, String cancelRequestId) {
        synchronized (lockOf(planId)) {
            PlanRow plan = repo.findPlanByPlanId(planId);
            if (plan == null) {
                return false;
            }
            if (isTerminal(plan.status()) || plan.cancelRequested()) {
                // 已结束或此前已登记取消：幂等返回，不重复补偿
                return false;
            }
            repo.markCancelRequested(planId, now());
            repo.insertEvent(event(plan, null, EventType.CANCEL_REQUESTED,
                    "cancelRequestId=" + cancelRequestId));
            return true;
        }
    }

    /**
     * 同 JVM 内重新加载未完成计划并继续：SUCCESS 节点不重启，已补偿节点不再补偿。
     * 若执行循环正在运行，则等待其自然推进，不另起执行循环。
     */
    public void resumePlan(String planId) {
        PlanRow plan = repo.findPlanByPlanId(planId);
        if (plan == null || isTerminal(plan.status())) {
            return;
        }
        repo.insertEvent(event(plan, null, EventType.PLAN_RESUMED,
                "reload unfinished plan within the same JVM"));
        executePlan(planId);
    }

    // ---------- 核心执行循环 ----------

    private void runPlan(String planId) {
        PlanRow plan = repo.findPlanByPlanId(planId);
        if (plan == null || isTerminal(plan.status())) {
            return;
        }
        List<NodeRow> nodes = repo.findNodesByPlanId(planId);
        if (nodes.isEmpty()) {
            return;
        }
        Map<String, List<String>> prerequisites = buildPrerequisites(
                nodes, repo.findDependenciesByPlanId(planId), repo.findConditionsByPlanId(planId));
        Map<String, ConditionRow> conditionByNode = new HashMap<>();
        for (ConditionRow c : repo.findConditionsByPlanId(planId)) {
            conditionByNode.put(c.nodeId(), c);
        }
        Map<String, Map<String, String>> paramsByNode = loadParams(planId);

        if (PlanStatus.PENDING.name().equals(plan.status())) {
            repo.updatePlanStatus(planId, PlanStatus.RUNNING.name(), now());
            repo.insertEvent(event(plan, null, EventType.PLAN_STARTED, null));
        }

        boolean progressed;
        do {
            progressed = false;

            // 每波次从 H2 重新加载状态：恢复时自动跳过已确认成功/已补偿节点
            List<NodeRow> current = repo.findNodesByPlanId(planId);
            Map<String, NodeState> states = new LinkedHashMap<>();
            for (NodeRow n : current) {
                states.put(n.nodeId(), NodeState.valueOf(n.state()));
            }
            boolean cancelRequested = repo.findPlanByPlanId(planId).cancelRequested();

            List<NodeRow> ready = new ArrayList<>();
            for (NodeRow node : current) {
                if (states.get(node.nodeId()) != NodeState.PENDING) {
                    continue;
                }
                Decision decision = evaluate(node, prerequisites.get(node.nodeId()),
                        conditionByNode.get(node.nodeId()), states, paramsByNode, cancelRequested);
                switch (decision.kind()) {
                    case START -> ready.add(node);
                    case SKIP -> {
                        persistNode(node.withRuntime(NodeState.SKIPPED.name(), node.compensated(),
                                decision.reason(), node.healthStatus(),
                                node.startedAt(), node.finishedAt()));
                        repo.insertEvent(event(plan, node.nodeId(), EventType.NODE_SKIPPED,
                                decision.reason()));
                        progressed = true;
                    }
                    case WAIT -> { /* 前置或条件引用尚未完成，后续波次再判 */ }
                }
            }

            // 启动本波就绪节点；启动等待期间可被取消打断
            for (NodeRow node : ready) {
                LaunchOutcome outcome = launchAndCheck(plan, node);
                progressed = true;
                if (outcome == LaunchOutcome.CANCELLED_BEFORE_LAUNCH) {
                    break;
                }
            }

            List<NodeRow> afterWave = repo.findNodesByPlanId(planId);
            boolean anyFailed = afterWave.stream()
                    .anyMatch(n -> NodeState.FAILED.name().equals(n.state()));
            cancelRequested = repo.findPlanByPlanId(planId).cancelRequested();

            // 取消：未启动（仍 PENDING）节点标记取消
            if (cancelRequested) {
                for (NodeRow node : repo.findNodesByPlanId(planId)) {
                    if (NodeState.PENDING.name().equals(node.state())) {
                        persistNode(node.withRuntime(NodeState.CANCELLED.name(),
                                node.compensated(), "cancelled before launch",
                                node.healthStatus(), node.startedAt(), node.finishedAt()));
                        repo.insertEvent(event(plan, node.nodeId(), EventType.NODE_CANCELLED,
                                "cancelled before launch"));
                        progressed = true;
                    }
                }
            }

            // 失败传播或取消：按逆依赖顺序补偿已启动节点
            if (anyFailed || cancelRequested) {
                compensateStartedNodes(plan, prerequisites, cancelRequested);
            }

            List<NodeRow> latest = repo.findNodesByPlanId(planId);
            boolean allDone = latest.stream()
                    .allMatch(n -> isTerminalNode(NodeState.valueOf(n.state())));
            if (allDone) {
                finishPlan(plan, latest, cancelRequested);
                return;
            }
        } while (progressed);

        // 无进展但仍有未完成节点：标记计划失败避免悬挂（正常路径不会到达）
        List<NodeRow> latest = repo.findNodesByPlanId(planId);
        if (latest.stream().anyMatch(n -> NodeState.FAILED.name().equals(n.state()))) {
            repo.updatePlanStatus(planId, PlanStatus.FAILED.name(), now());
            repo.insertEvent(event(repo.findPlanByPlanId(planId), null, EventType.PLAN_FAILED,
                    "plan failed with failed nodes"));
        }
    }

    // ---------- 启动 + 健康检查（模拟，无真实进程） ----------

    private LaunchOutcome launchAndCheck(PlanRow plan, NodeRow node) {
        repo.insertEvent(event(plan, node.nodeId(), EventType.NODE_LAUNCHING,
                node.launchDelayMs() > 0 ? "launchDelayMs=" + node.launchDelayMs() : null));

        // 启动延迟：切片睡眠，期间观测取消标志，取消则不启动该节点
        long remaining = node.launchDelayMs();
        while (remaining > 0) {
            if (repo.findPlanByPlanId(plan.planId()).cancelRequested()) {
                NodeRow cancelled = node.withRuntime(NodeState.CANCELLED.name(),
                        node.compensated(), "cancelled during launch delay",
                        node.healthStatus(), node.startedAt(), node.finishedAt());
                persistNode(cancelled);
                repo.insertEvent(event(plan, node.nodeId(), EventType.NODE_CANCELLED,
                        "cancelled during launch delay"));
                return LaunchOutcome.CANCELLED_BEFORE_LAUNCH;
            }
            long slice = Math.min(DELAY_SLICE_MS, remaining);
            try {
                Thread.sleep(slice);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                NodeRow cancelled = node.withRuntime(NodeState.CANCELLED.name(),
                        node.compensated(), "interrupted during launch delay",
                        node.healthStatus(), node.startedAt(), node.finishedAt());
                persistNode(cancelled);
                repo.insertEvent(event(plan, node.nodeId(), EventType.NODE_CANCELLED,
                        "interrupted during launch delay"));
                return LaunchOutcome.CANCELLED_BEFORE_LAUNCH;
            }
            remaining -= slice;
        }
        // 延迟结束后、真正启动前再检查一次
        if (repo.findPlanByPlanId(plan.planId()).cancelRequested()) {
            NodeRow cancelled = node.withRuntime(NodeState.CANCELLED.name(),
                    node.compensated(), "cancelled before launch",
                    node.healthStatus(), node.startedAt(), node.finishedAt());
            persistNode(cancelled);
            repo.insertEvent(event(plan, node.nodeId(), EventType.NODE_CANCELLED,
                    "cancelled before launch"));
            return LaunchOutcome.CANCELLED_BEFORE_LAUNCH;
        }

        if (node.simulateStartFailure()) {
            NodeRow failed = node.withRuntime(NodeState.FAILED.name(), node.compensated(),
                    "simulated node start failure", node.healthStatus(),
                    node.startedAt(), now());
            persistNode(failed);
            repo.insertEvent(event(plan, node.nodeId(), EventType.NODE_START_FAILED,
                    "simulated node start failure"));
            return LaunchOutcome.FAILED;
        }

        LocalDateTime startedAt = now();
        NodeRow started = node.withRuntime(NodeState.STARTED.name(), node.compensated(),
                null, node.healthStatus(), startedAt, node.finishedAt());
        persistNode(started);
        repo.insertEvent(event(plan, node.nodeId(), EventType.NODE_STARTED, null));

        // 同步健康检查（模拟）
        if (node.healthShouldFail()) {
            NodeRow unhealthy = started.withRuntime(NodeState.FAILED.name(), started.compensated(),
                    "health check failed: " + safeMessage(node.healthMessage()),
                    HealthStatus.FAILED.name(), startedAt, now());
            persistNode(unhealthy);
            repo.insertEvent(event(plan, node.nodeId(), EventType.HEALTH_CHECK_FAILED,
                    safeMessage(node.healthMessage())));
            return LaunchOutcome.FAILED;
        }
        NodeRow healthy = started.withRuntime(NodeState.SUCCESS.name(), started.compensated(),
                null, HealthStatus.PASSED.name(), startedAt, now());
        persistNode(healthy);
        repo.insertEvent(event(plan, node.nodeId(), EventType.HEALTH_CHECK_PASSED,
                safeMessage(node.healthMessage())));
        return LaunchOutcome.SUCCEEDED;
    }

    private enum LaunchOutcome {SUCCEEDED, FAILED, CANCELLED_BEFORE_LAUNCH}

    // ---------- 补偿（逆依赖顺序，仅一次） ----------

    private void compensateStartedNodes(PlanRow plan, Map<String, List<String>> prerequisites,
                                        boolean cancelRequested) {
        List<NodeRow> nodes = repo.findNodesByPlanId(plan.planId());

        // 已启动 = STARTED / SUCCESS；以及“启动成功但健康检查失败”的节点
        // （state=FAILED 且 started_at 非空；启动即失败的节点 started_at 为空，不补偿）
        Set<String> started = new LinkedHashSet<>();
        for (NodeRow n : nodes) {
            NodeState st = NodeState.valueOf(n.state());
            boolean launchedThenUnhealthy = st == NodeState.FAILED && n.startedAt() != null;
            if ((st == NodeState.STARTED || st == NodeState.SUCCESS || launchedThenUnhealthy)
                    && !n.compensated()) {
                started.add(n.nodeId());
            }
        }
        if (started.isEmpty()) {
            return;
        }

        // 逆依赖顺序：在已启动节点构成的前置子图上做拓扑排序（前置在前）后反转，
        // 保证被依赖者（如 db）最后补偿。
        Map<String, List<String>> subAdj = new LinkedHashMap<>();
        for (String id : started) {
            subAdj.put(id, new ArrayList<>(prerequisites.getOrDefault(id, List.of())
                    .stream().filter(started::contains).toList()));
        }
        List<String> topo;
        try {
            topo = DagCycleDetector.topologicalSort(subAdj);
        } catch (IllegalStateException ex) {
            // 提交期已保证无环；兜底按声明顺序反转
            topo = new ArrayList<>(started);
            Collections.reverse(topo);
        }
        List<String> compensationOrder = new ArrayList<>(topo);
        Collections.reverse(compensationOrder);

        for (String id : compensationOrder) {
            NodeRow node = repo.findNodesByPlanId(plan.planId()).stream()
                    .filter(x -> x.nodeId().equals(id)).findFirst().orElseThrow();
            // 双重检查：只补偿一次
            if (node.compensated()) {
                continue;
            }
            NodeState currentState = NodeState.valueOf(node.state());
            boolean launchedThenUnhealthy =
                    currentState == NodeState.FAILED && node.startedAt() != null;
            if (currentState != NodeState.STARTED && currentState != NodeState.SUCCESS
                    && !launchedThenUnhealthy) {
                continue;
            }

            if (node.compensation() == null || node.compensation().isBlank()) {
                if (cancelRequested) {
                    // 取消要求已启动节点进入已取消；无补偿动作则直接迁移并记 compensated
                    NodeRow cancelled = node.withRuntime(NodeState.CANCELLED.name(), true,
                            null, node.healthStatus(), node.startedAt(), now());
                    persistNode(cancelled);
                    repo.insertEvent(event(plan, id, EventType.NODE_CANCELLED,
                            "cancelled after launch; no explicit compensation declared"));
                }
                // 失败回滚路径下未声明补偿的节点保持原状态（无回滚动作可执行）
                continue;
            }

            repo.insertEvent(event(plan, id, EventType.COMPENSATE_STARTED, node.compensation()));
            if (node.simulateCompFailure()) {
                // 补偿失败同样置 compensated=1：补偿只执行一次，不做重试风暴
                NodeRow compFailed = node.withRuntime(NodeState.FAILED.name(), true,
                        "compensation failed: " + node.compensation(), node.healthStatus(),
                        node.startedAt(), now());
                persistNode(compFailed);
                repo.insertEvent(event(plan, id, EventType.COMPENSATE_FAILED,
                        "simulated compensation failure: " + node.compensation()));
            } else if (cancelRequested) {
                NodeRow cancelled = node.withRuntime(NodeState.CANCELLED.name(), true,
                        null, node.healthStatus(), node.startedAt(), now());
                persistNode(cancelled);
                repo.insertEvent(event(plan, id, EventType.COMPENSATE_SUCCEEDED,
                        node.compensation()));
                repo.insertEvent(event(plan, id, EventType.NODE_COMPENSATED,
                        node.compensation()));
            } else {
                // 失败回滚：补偿成功后保留 FAILED（若本节点即健康检查失败节点）
                // 或迁移到 COMPENSATED（此前健康的节点），均置 compensated=1 不重复补偿
                String targetState = launchedThenUnhealthy
                        ? NodeState.FAILED.name() : NodeState.COMPENSATED.name();
                String reason = launchedThenUnhealthy ? node.failureReason() : null;
                NodeRow compensated = node.withRuntime(targetState, true, reason,
                        node.healthStatus(), node.startedAt(), now());
                persistNode(compensated);
                repo.insertEvent(event(plan, id, EventType.COMPENSATE_SUCCEEDED,
                        node.compensation()));
                repo.insertEvent(event(plan, id, EventType.NODE_COMPENSATED,
                        node.compensation()));
            }
        }
    }

    private void finishPlan(PlanRow plan, List<NodeRow> nodes, boolean cancelRequested) {
        boolean hasFailed = nodes.stream().anyMatch(n ->
                NodeState.FAILED.name().equals(n.state()));
        String status;
        EventType eventType;
        String detail;
        if (cancelRequested) {
            status = PlanStatus.CANCELLED.name();
            eventType = EventType.PLAN_CANCELLED;
            detail = null;
        } else if (hasFailed) {
            status = PlanStatus.FAILED.name();
            eventType = EventType.PLAN_FAILED;
            detail = "one or more nodes failed; successors blocked and compensation executed";
        } else {
            status = PlanStatus.SUCCESS.name();
            eventType = EventType.PLAN_SUCCEEDED;
            detail = null;
        }
        repo.updatePlanStatus(plan.planId(), status, now());
        repo.insertEvent(event(plan, null, eventType, detail));
    }

    // ---------- 节点可启动性判定 ----------

    private Decision evaluate(NodeRow node, List<String> prereqIds, ConditionRow condition,
                              Map<String, NodeState> states,
                              Map<String, Map<String, String>> paramsByNode,
                              boolean cancelRequested) {
        if (cancelRequested) {
            return Decision.wait_();
        }

        List<String> unresolved = new ArrayList<>();
        List<String> blockedPrereqs = new ArrayList<>();
        for (String prereqId : prereqIds) {
            NodeState pr = states.get(prereqId);
            if (pr == null) {
                continue;
            }
            if (pr == NodeState.FAILED || pr == NodeState.SKIPPED
                    || pr == NodeState.COMPENSATED || pr == NodeState.CANCELLED) {
                blockedPrereqs.add(prereqId);
            } else if (pr == NodeState.PENDING || pr == NodeState.STARTED) {
                unresolved.add(prereqId);
            }
        }
        if (!blockedPrereqs.isEmpty()) {
            return Decision.skip("dependency failed or blocked: " + blockedPrereqs);
        }
        if (!unresolved.isEmpty()) {
            return Decision.wait_();
        }

        if (condition != null) {
            ConditionResult result = evaluateCondition(condition, states, paramsByNode);
            if (result.waiting()) {
                return Decision.wait_();
            }
            if (!result.satisfied()) {
                return Decision.skip(result.reason());
            }
        }
        return Decision.start();
    }

    private ConditionResult evaluateCondition(ConditionRow condition, Map<String, NodeState> states,
                                              Map<String, Map<String, String>> paramsByNode) {
        String ref = condition.refNodeId();
        NodeState refState = states.get(ref);
        switch (condition.predicate()) {
            case "NODE_SUCCESS" -> {
                if (refState == NodeState.SUCCESS) {
                    return ConditionResult.SATISFIED;
                }
                if (refState == NodeState.PENDING || refState == NodeState.STARTED) {
                    return ConditionResult.WAITING;
                }
                return ConditionResult.unmet("condition NODE_SUCCESS unmet: reference node "
                        + ref + " is " + refState);
            }
            case "PARAM_EQUALS" -> {
                if (refState == NodeState.PENDING || refState == NodeState.STARTED) {
                    return ConditionResult.WAITING;
                }
                Map<String, String> refParams = paramsByNode.getOrDefault(ref, Map.of());
                String actual = refParams.get(condition.paramName());
                if (condition.expectedValue() != null
                        && condition.expectedValue().equals(actual)) {
                    return ConditionResult.SATISFIED;
                }
                return ConditionResult.unmet("condition PARAM_EQUALS unmet: " + ref + "."
                        + condition.paramName() + " expected=" + condition.expectedValue()
                        + " actual=" + actual);
            }
            default -> {
                return ConditionResult.unmet(
                        "unsupported condition predicate: " + condition.predicate());
            }
        }
    }

    // ---------- 图构建 ----------

    /**
     * 合并显式依赖与条件引用（隐式依赖），去重后形成节点前置集合。
     */
    private Map<String, List<String>> buildPrerequisites(List<NodeRow> nodes,
                                                         List<DependencyRow> deps,
                                                         List<ConditionRow> conditions) {
        Map<String, LinkedHashSet<String>> merged = new LinkedHashMap<>();
        for (NodeRow n : nodes) {
            merged.put(n.nodeId(), new LinkedHashSet<>());
        }
        for (DependencyRow d : deps) {
            merged.computeIfAbsent(d.nodeId(), k -> new LinkedHashSet<>()).add(d.dependsOn());
        }
        for (ConditionRow c : conditions) {
            merged.computeIfAbsent(c.nodeId(), k -> new LinkedHashSet<>()).add(c.refNodeId());
        }
        Map<String, List<String>> result = new LinkedHashMap<>();
        merged.forEach((k, v) -> result.put(k, new ArrayList<>(v)));
        return result;
    }

    private Map<String, Map<String, String>> loadParams(String planId) {
        Map<String, Map<String, String>> paramsByNode = new HashMap<>();
        for (ParamRow p : repo.findParamsByPlanId(planId)) {
            paramsByNode.computeIfAbsent(p.nodeId(), k -> new LinkedHashMap<>())
                    .put(p.paramKey(), p.paramValue());
        }
        return paramsByNode;
    }

    // ---------- 辅助类型与方法 ----------

    private record Decision(Kind kind, String reason) {
        static Decision start() {
            return new Decision(Kind.START, null);
        }

        static Decision skip(String reason) {
            return new Decision(Kind.SKIP, reason);
        }

        static Decision wait_() {
            return new Decision(Kind.WAIT, null);
        }
    }

    private enum Kind {START, SKIP, WAIT}

    private record ConditionResult(boolean satisfied, boolean waiting, String reason) {
        static final ConditionResult SATISFIED = new ConditionResult(true, false, null);
        static final ConditionResult WAITING = new ConditionResult(false, true, null);

        static ConditionResult unmet(String reason) {
            return new ConditionResult(false, false, reason);
        }
    }

    private void persistNode(NodeRow row) {
        repo.updateNodeRuntime(row);
    }

    private EventRow event(PlanRow plan, String nodeId, EventType type, String detail) {
        return new EventRow(null, plan.planId(), plan.version(), plan.requestId(),
                nodeId, type.name(), detail, now());
    }

    private static String safeMessage(String message) {
        return message == null ? "" : message;
    }

    private LocalDateTime now() {
        return LocalDateTime.now(clock);
    }

    private Object lockOf(String planId) {
        return planLocks.computeIfAbsent(planId, k -> new Object());
    }

    private static boolean isTerminal(String status) {
        return PlanStatus.SUCCESS.name().equals(status)
                || PlanStatus.FAILED.name().equals(status)
                || PlanStatus.CANCELLED.name().equals(status);
    }

    private static boolean isTerminalNode(NodeState state) {
        return state == NodeState.SUCCESS || state == NodeState.FAILED
                || state == NodeState.SKIPPED || state == NodeState.COMPENSATED
                || state == NodeState.CANCELLED;
    }
}
