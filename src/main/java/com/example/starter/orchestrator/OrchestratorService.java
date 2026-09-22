package com.example.starter.orchestrator;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import com.example.starter.orchestrator.PlanRepository.NodeRow;
import com.example.starter.orchestrator.PlanRepository.PlanRow;
import com.example.starter.orchestrator.dto.EventView;
import com.example.starter.orchestrator.dto.NodeSpec;
import com.example.starter.orchestrator.dto.NodeView;
import com.example.starter.orchestrator.dto.PlanSubmitRequest;
import com.example.starter.orchestrator.dto.PlanView;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * ROS 2 Launch 风格依赖编排服务（本地模拟，不触发真实进程）。
 * 负责计划提交的幂等控制、DAG 解析与循环检测、按依赖顺序执行、
 * 失败补偿、幂等取消以及同一 JVM 内未完成计划的恢复。
 */
@Service
public class OrchestratorService {

    private final PlanRepository repository;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final Map<String, Object> planLocks = new ConcurrentHashMap<>();

    public OrchestratorService(PlanRepository repository, ObjectMapper objectMapper, Clock clock) {
        this.repository = repository;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    /**
     * 提交计划。同一 requestId 同参提交幂等返回既有计划，异参提交抛出 409 冲突。
     * 提交时解析 DAG，显式依赖或条件引用构成循环时抛出携带完整路径的 400 异常。
     */
    public PlanView submit(PlanSubmitRequest request) {
        validate(request);
        String payloadHash = hash(request);
        PlanRow plan = new PlanRow(request.planId(), request.version(), request.requestId(),
                payloadHash, PlanStatus.READY, now(), now());
        synchronized (lockFor(request.planId())) {
            try {
                repository.insertPlan(plan);
                for (NodeSpec spec : request.nodes()) {
                    repository.insertNode(toRow(request.planId(), spec));
                }
                repository.insertEvent(request.planId(), null, EventType.PLAN_SUBMITTED,
                        "version=" + request.version(), now());
            } catch (DuplicateKeyException e) {
                PlanRow existing = repository.findPlanByRequestId(request.requestId())
                        .orElseThrow(() -> new PlanConflictException("计划标识已被占用: " + request.planId()));
                if (!existing.payloadHash().equals(payloadHash) || !existing.planId().equals(request.planId())) {
                    throw new PlanConflictException("requestId 已存在且计划内容不一致: " + request.requestId());
                }
                return view(existing, true);
            }
            return view(repository.findPlan(request.planId()).orElseThrow(), false);
        }
    }

    /**
     * 执行计划：仅前置依赖成功且条件满足的节点进入已启动状态；
     * 依赖失败传播并阻断后继；健康检查失败触发逆依赖顺序补偿。
     */
    public PlanView execute(String planId) {
        PlanRow plan = repository.findPlan(planId).orElseThrow(() -> new PlanNotFoundException(planId));
        if (plan.status() != PlanStatus.READY) {
            throw new PlanStateException("计划状态不允许执行: " + plan.status());
        }
        return run(planId, PlanStatus.READY, EventType.PLAN_STARTED);
    }

    /**
     * 在同一 JVM 内恢复未完成计划：已确认成功的节点不重复启动，
     * 已完成的补偿不重复执行，仅继续处理未决节点。
     */
    public PlanView resume(String planId) {
        PlanRow plan = repository.findPlan(planId).orElseThrow(() -> new PlanNotFoundException(planId));
        if (plan.status() != PlanStatus.RUNNING && plan.status() != PlanStatus.FAILED) {
            throw new PlanStateException("计划状态不允许恢复: " + plan.status());
        }
        return run(planId, plan.status(), EventType.PLAN_RESUMED);
    }

    /**
     * 幂等取消：未启动节点标记取消；已启动节点进入已取消且补偿只执行一次。
     * 已取消的计划重复取消直接返回当前状态，不产生重复事件。
     */
    public PlanView cancel(String planId) {
        synchronized (lockFor(planId)) {
            PlanRow plan = repository.findPlan(planId).orElseThrow(() -> new PlanNotFoundException(planId));
            if (plan.status() == PlanStatus.CANCELLED) {
                return view(plan, true);
            }
            if (plan.status() == PlanStatus.SUCCEEDED || plan.status() == PlanStatus.FAILED) {
                throw new PlanStateException("计划已终结，无法取消: " + plan.status());
            }
            repository.updatePlanStatus(planId, PlanStatus.CANCELLED, now());
            List<NodeRow> nodes = repository.findNodes(planId);
            for (NodeRow node : nodes) {
                switch (node.state()) {
                    case PENDING, BLOCKED, SKIPPED -> {
                        repository.updateNodeState(planId, node.nodeKey(), NodeState.CANCELLED);
                        repository.insertEvent(planId, node.nodeKey(), EventType.NODE_CANCELLED,
                                "未启动节点取消", now());
                    }
                    case RUNNING, HEALTHY -> {
                        compensateNode(planId, node);
                        repository.updateNodeState(planId, node.nodeKey(), NodeState.CANCELLED);
                        repository.insertEvent(planId, node.nodeKey(), EventType.NODE_CANCELLED,
                                "已启动节点取消", now());
                    }
                    default -> {
                        // FAILED/COMPENSATED/CANCELLED 保持原状态
                    }
                }
            }
            repository.insertEvent(planId, null, EventType.PLAN_CANCELLED, null, now());
            return view(repository.findPlan(planId).orElseThrow(), false);
        }
    }

    /**
     * 查询计划当前视图。
     */
    public PlanView getPlan(String planId) {
        PlanRow plan = repository.findPlan(planId).orElseThrow(() -> new PlanNotFoundException(planId));
        return view(plan, false);
    }

    /**
     * 查询计划事件日志。
     */
    public List<EventView> getEvents(String planId) {
        repository.findPlan(planId).orElseThrow(() -> new PlanNotFoundException(planId));
        return repository.findEvents(planId);
    }

    private PlanView run(String planId, PlanStatus expected, EventType startEvent) {
        PlanRow plan = repository.findPlan(planId).orElseThrow(() -> new PlanNotFoundException(planId));
        if (plan.status() != expected) {
            throw new PlanStateException("计划状态已变化，无法继续: " + plan.status());
        }
        repository.updatePlanStatus(planId, PlanStatus.RUNNING, now());
        repository.insertEvent(planId, null, startEvent, null, now());

        List<NodeRow> nodes = repository.findNodes(planId);
        Map<String, Set<String>> prereqs = prerequisitesOf(nodes);
        boolean failed = false;
        for (String nodeKey : DagUtils.topoOrder(prereqs)) {
            NodeRow node = repository.findNode(planId, nodeKey).orElseThrow();
            if (node.state() != NodeState.PENDING) {
                // 已 HEALTHY/COMPENSATED/CANCELLED 等节点不重复启动
                if (node.state() == NodeState.FAILED || node.state() == NodeState.BLOCKED) {
                    failed = true;
                }
                continue;
            }
            if (isCancelled(planId)) {
                break;
            }
            Map<String, NodeRow> current = indexByKey(repository.findNodes(planId));
            if (!depsSucceeded(node, current)) {
                repository.updateNodeState(planId, nodeKey, NodeState.BLOCKED);
                repository.insertEvent(planId, nodeKey, EventType.NODE_BLOCKED,
                        "前置依赖未成功", now());
                failed = true;
                continue;
            }
            if (!conditionSatisfied(node, current)) {
                repository.updateNodeState(planId, nodeKey, NodeState.SKIPPED);
                repository.insertEvent(planId, nodeKey, EventType.NODE_SKIPPED,
                        "条件谓词不满足", now());
                continue;
            }
            repository.updateNodeState(planId, nodeKey, NodeState.RUNNING);
            repository.insertEvent(planId, nodeKey, EventType.NODE_STARTED, null, now());
            sleep(node.delayMillis());
            if (isCancelled(planId)) {
                break;
            }
            if ("PASS".equals(node.healthCheck())) {
                repository.updateNodeState(planId, nodeKey, NodeState.HEALTHY);
                repository.insertEvent(planId, nodeKey, EventType.NODE_HEALTHY, null, now());
            } else {
                repository.updateNodeState(planId, nodeKey, NodeState.FAILED);
                repository.insertEvent(planId, nodeKey, EventType.NODE_FAILED,
                        "健康检查失败", now());
                compensateStarted(planId);
                blockPending(planId);
                failed = true;
                break;
            }
        }
        PlanStatus finalStatus = finalizeStatus(planId, failed);
        if (finalStatus != PlanStatus.CANCELLED) {
            repository.updatePlanStatus(planId, finalStatus, now());
            repository.insertEvent(planId, null,
                    finalStatus == PlanStatus.SUCCEEDED ? EventType.PLAN_SUCCEEDED : EventType.PLAN_FAILED,
                    null, now());
        }
        return view(repository.findPlan(planId).orElseThrow(), false);
    }

    private void compensateStarted(String planId) {
        List<NodeRow> nodes = repository.findNodes(planId);
        Map<String, Set<String>> prereqs = prerequisitesOf(nodes);
        List<String> topo = DagUtils.topoOrder(prereqs);
        Map<String, NodeRow> byKey = indexByKey(nodes);
        List<NodeRow> started = new ArrayList<>();
        for (String key : topo) {
            NodeRow node = byKey.get(key);
            if (node.state() == NodeState.HEALTHY) {
                started.add(node);
            }
        }
        // 逆依赖顺序执行补偿
        for (int i = started.size() - 1; i >= 0; i--) {
            compensateNode(planId, started.get(i));
        }
    }

    private void compensateNode(String planId, NodeRow node) {
        if (node.compensation() == null || node.compensation().isBlank()) {
            return;
        }
        // 数据库原子标记保证补偿只执行一次
        if (repository.markCompensated(planId, node.nodeKey())) {
            repository.insertEvent(planId, node.nodeKey(), EventType.COMPENSATION_EXECUTED,
                    node.compensation(), now());
            if (node.state() == NodeState.HEALTHY) {
                repository.updateNodeState(planId, node.nodeKey(), NodeState.COMPENSATED);
            }
        }
    }

    private void blockPending(String planId) {
        for (NodeRow node : repository.findNodes(planId)) {
            if (node.state() == NodeState.PENDING) {
                repository.updateNodeState(planId, node.nodeKey(), NodeState.BLOCKED);
                repository.insertEvent(planId, node.nodeKey(), EventType.NODE_BLOCKED,
                        "因节点失败阻断", now());
            }
        }
    }

    private PlanStatus finalizeStatus(String planId, boolean failed) {
        PlanRow plan = repository.findPlan(planId).orElseThrow();
        if (plan.status() == PlanStatus.CANCELLED) {
            return PlanStatus.CANCELLED;
        }
        if (failed) {
            return PlanStatus.FAILED;
        }
        for (NodeRow node : repository.findNodes(planId)) {
            if (node.state() == NodeState.FAILED || node.state() == NodeState.BLOCKED
                    || node.state() == NodeState.PENDING || node.state() == NodeState.RUNNING) {
                return PlanStatus.FAILED;
            }
        }
        return PlanStatus.SUCCEEDED;
    }

    private boolean depsSucceeded(NodeRow node, Map<String, NodeRow> current) {
        for (String dep : parseDeps(node.depsJson())) {
            NodeState state = current.get(dep).state();
            if (state != NodeState.HEALTHY && state != NodeState.COMPENSATED) {
                return false;
            }
        }
        return true;
    }

    private boolean conditionSatisfied(NodeRow node, Map<String, NodeRow> current) {
        if (node.conditionRef() == null) {
            return true;
        }
        NodeRow ref = current.get(node.conditionRef());
        return ref != null && ref.state().name().equals(node.conditionExpect());
    }

    private boolean isCancelled(String planId) {
        return repository.findPlan(planId).orElseThrow().status() == PlanStatus.CANCELLED;
    }

    private void validate(PlanSubmitRequest request) {
        Map<String, List<String>> explicitDeps = new LinkedHashMap<>();
        Map<String, String> conditionRefs = new LinkedHashMap<>();
        for (NodeSpec spec : request.nodes()) {
            if (explicitDeps.put(spec.key(), spec.dependsOn() == null ? List.of() : spec.dependsOn()) != null) {
                throw new PlanStateException("节点标识重复: " + spec.key());
            }
            if (spec.condition() != null) {
                conditionRefs.put(spec.key(), spec.condition().ref());
            }
        }
        for (NodeSpec spec : request.nodes()) {
            for (String dep : spec.dependsOn() == null ? List.<String>of() : spec.dependsOn()) {
                if (!explicitDeps.containsKey(dep)) {
                    throw new PlanStateException("依赖的节点不存在: " + dep);
                }
            }
            if (spec.condition() != null && !explicitDeps.containsKey(spec.condition().ref())) {
                throw new PlanStateException("条件引用的节点不存在: " + spec.condition().ref());
            }
        }
        List<String> cycle = DagUtils.findCycle(DagUtils.prerequisites(explicitDeps, conditionRefs));
        if (cycle != null) {
            throw new CycleDependencyException(cycle);
        }
    }

    private NodeRow toRow(String planId, NodeSpec spec) {
        return new NodeRow(planId, spec.key(), writeJson(spec.params()), spec.delayMillis(),
                spec.condition() == null ? null : spec.condition().ref(),
                spec.condition() == null ? null : spec.condition().expect(),
                writeJson(spec.dependsOn() == null ? List.of() : spec.dependsOn()),
                spec.healthCheck(), spec.compensation(), NodeState.PENDING, false);
    }

    private Map<String, Set<String>> prerequisitesOf(List<NodeRow> nodes) {
        Map<String, List<String>> explicitDeps = new LinkedHashMap<>();
        Map<String, String> conditionRefs = new LinkedHashMap<>();
        for (NodeRow node : nodes) {
            explicitDeps.put(node.nodeKey(), parseDeps(node.depsJson()));
            if (node.conditionRef() != null) {
                conditionRefs.put(node.nodeKey(), node.conditionRef());
            }
        }
        return DagUtils.prerequisites(explicitDeps, conditionRefs);
    }

    private List<String> parseDeps(String depsJson) {
        try {
            return objectMapper.readValue(depsJson,
                    objectMapper.getTypeFactory().constructCollectionType(List.class, String.class));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("依赖列表反序列化失败", e);
        }
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("序列化失败", e);
        }
    }

    private String hash(PlanSubmitRequest request) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashed = digest.digest(writeJson(request).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hashed);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    private PlanView view(PlanRow plan, boolean idempotentReplay) {
        List<NodeView> nodes = repository.findNodes(plan.planId()).stream()
                .map(n -> new NodeView(n.nodeKey(), n.state().name(), n.compensated()))
                .toList();
        return new PlanView(plan.planId(), plan.version(), plan.requestId(), plan.status().name(),
                idempotentReplay, nodes);
    }

    private Map<String, NodeRow> indexByKey(List<NodeRow> nodes) {
        Map<String, NodeRow> byKey = new LinkedHashMap<>();
        for (NodeRow node : nodes) {
            byKey.put(node.nodeKey(), node);
        }
        return byKey;
    }

    private Object lockFor(String planId) {
        return planLocks.computeIfAbsent(planId, k -> new Object());
    }

    private Instant now() {
        return Instant.now(clock);
    }

    private void sleep(long millis) {
        if (millis <= 0) {
            return;
        }
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
