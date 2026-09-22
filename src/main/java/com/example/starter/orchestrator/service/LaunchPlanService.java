package com.example.starter.orchestrator.service;

import com.example.starter.orchestrator.CyclicDependencyException;
import com.example.starter.orchestrator.IdempotencyConflictException;
import com.example.starter.orchestrator.InvalidPlanException;
import com.example.starter.orchestrator.NotFoundException;
import com.example.starter.orchestrator.api.CancelPlanRequest;
import com.example.starter.orchestrator.api.PlanResponse;
import com.example.starter.orchestrator.api.SubmitPlanRequest;
import com.example.starter.orchestrator.domain.EventType;
import com.example.starter.orchestrator.domain.NodeState;
import com.example.starter.orchestrator.domain.PlanStatus;
import com.example.starter.orchestrator.engine.DagCycleDetector;
import com.example.starter.orchestrator.engine.PlanEngine;
import com.example.starter.orchestrator.repo.ConditionRow;
import com.example.starter.orchestrator.repo.DependencyRow;
import com.example.starter.orchestrator.repo.EventRow;
import com.example.starter.orchestrator.repo.NodeRow;
import com.example.starter.orchestrator.repo.OrchestratorRepository;
import com.example.starter.orchestrator.repo.ParamRow;
import com.example.starter.orchestrator.repo.PlanRow;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.Executor;

/**
 * 计划提交（幂等）、查询、取消、恢复的应用服务。
 */
@Service
public class LaunchPlanService {

    private static final Logger log = LoggerFactory.getLogger(LaunchPlanService.class);

    private final OrchestratorRepository repo;
    private final PlanEngine engine;
    private final Executor planExecutor;
    private final Clock clock;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactionTemplate;

    public LaunchPlanService(OrchestratorRepository repo, PlanEngine engine,
                             @Qualifier("planExecutor") Executor planExecutor,
                             Clock clock, ObjectMapper objectMapper,
                             PlatformTransactionManager transactionManager) {
        this.repo = repo;
        this.engine = engine;
        this.planExecutor = planExecutor;
        this.clock = clock;
        this.objectMapper = objectMapper;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    /**
     * 提交计划并异步执行。
     * 同一 requestId 同参提交：幂等返回既有计划；同 requestId 异参：409。
     */
    public PlanResponse submit(SubmitPlanRequest request) {
        validateStructure(request);
        String payloadHash = canonicalHash(request);

        // 先读：命中已提交 request 时做幂等/冲突判定
        PlanRow existing = repo.findPlanByRequestId(request.requestId());
        if (existing != null) {
            return reconcile(existing, payloadHash, request);
        }

        LocalDateTime now = LocalDateTime.now(clock);
        PlanRow plan = new PlanRow(null, request.planId(), request.version(),
                request.requestId(), request.planName(), PlanStatus.PENDING.name(),
                payloadHash, false, now, now);
        try {
            transactionTemplate.executeWithoutResult(status -> persistNewPlan(request, plan, now));
        } catch (DuplicateKeyException dup) {
            // 并发提交：唯一约束兜底。事务已回滚，使用新语句重新读取判定
            PlanRow raced = repo.findPlanByRequestId(request.requestId());
            if (raced != null) {
                return reconcile(raced, payloadHash, request);
            }
            PlanRow samePlanId = repo.findPlanByPlanId(request.planId());
            if (samePlanId != null) {
                throw new IdempotencyConflictException(
                        "planId already exists: " + request.planId());
            }
            throw dup;
        }

        repo.insertEvent(new EventRow(null, request.planId(), request.version(),
                request.requestId(), null, EventType.PLAN_SUBMITTED.name(),
                "plan submitted with " + request.nodes().size() + " node(s)", now));

        // 异步执行：取消请求可在执行过程中通过 cancel_requested 标志介入
        planExecutor.execute(() -> engine.executePlan(request.planId()));
        return toResponse(repo.findPlanByPlanId(request.planId()));
    }

    public PlanResponse getPlan(String planId) {
        PlanRow plan = requirePlan(planId);
        return toResponse(plan);
    }

    /**
     * 取消计划（幂等）：未启动节点标记取消，已启动节点取消并仅补偿一次。
     * 执行循环观测到取消标志后完成取消流程。
     */
    public PlanResponse cancel(String planId, CancelPlanRequest request) {
        requirePlan(planId);
        engine.cancelPlan(planId, request.requestId());
        return getPlan(planId);
    }

    /**
     * 同 JVM 内重新加载未完成计划并恢复执行（调用线程内驱动到终态或等待态）。
     */
    public PlanResponse resume(String planId) {
        requirePlan(planId);
        engine.resumePlan(planId);
        return getPlan(planId);
    }

    // ---------- 校验与持久化 ----------

    private PlanResponse reconcile(PlanRow existing, String payloadHash, SubmitPlanRequest request) {
        if (!existing.planId().equals(request.planId())) {
            throw new IdempotencyConflictException(
                    "requestId " + request.requestId() + " already used by another plan");
        }
        if (!payloadHash.equals(existing.payloadHash())) {
            throw new IdempotencyConflictException(
                    "requestId " + request.requestId()
                            + " reused with different parameters");
        }
        // 幂等命中：同参重复提交返回既有计划，不重启任何节点
        return toResponse(existing);
    }

    private void validateStructure(SubmitPlanRequest request) {
        Set<String> ids = new LinkedHashSet<>();
        for (SubmitPlanRequest.NodeSpec node : request.nodes()) {
            if (!ids.add(node.nodeId())) {
                throw new InvalidPlanException("duplicate nodeId: " + node.nodeId());
            }
        }
        for (SubmitPlanRequest.NodeSpec node : request.nodes()) {
            if (node.dependsOn() != null) {
                for (String dep : node.dependsOn()) {
                    if (!ids.contains(dep)) {
                        throw new InvalidPlanException(
                                "node " + node.nodeId() + " depends on unknown node: " + dep);
                    }
                    if (dep.equals(node.nodeId())) {
                        throw new CyclicDependencyException(
                                "cyclic dependency detected: " + node.nodeId() + " -> " + node.nodeId());
                    }
                }
            }
            if (node.condition() != null) {
                SubmitPlanRequest.ConditionSpec cond = node.condition();
                if (!ids.contains(cond.referenceNode())) {
                    throw new InvalidPlanException("node " + node.nodeId()
                            + " condition references unknown node: " + cond.referenceNode());
                }
                if (!"NODE_SUCCESS".equals(cond.predicate())
                        && !"PARAM_EQUALS".equals(cond.predicate())) {
                    throw new InvalidPlanException(
                            "unsupported condition predicate: " + cond.predicate());
                }
                if ("PARAM_EQUALS".equals(cond.predicate())
                        && (cond.parameterName() == null || cond.parameterName().isBlank())) {
                    throw new InvalidPlanException(
                            "PARAM_EQUALS condition requires parameterName on node " + node.nodeId());
                }
            }
        }

        // 合并显式依赖与条件引用构成图，执行前发现循环并给出完整依赖路径
        Map<String, List<String>> adjacency = new LinkedHashMap<>();
        for (String id : ids) {
            adjacency.put(id, new ArrayList<>());
        }
        for (SubmitPlanRequest.NodeSpec node : request.nodes()) {
            if (node.dependsOn() != null) {
                adjacency.get(node.nodeId()).addAll(node.dependsOn());
            }
            if (node.condition() != null) {
                adjacency.get(node.nodeId()).add(node.condition().referenceNode());
            }
        }
        List<String> cycle = DagCycleDetector.findCycle(adjacency);
        if (cycle != null) {
            throw new CyclicDependencyException(
                    "cyclic dependency detected: " + String.join(" -> ", cycle));
        }
    }

    private void persistNewPlan(SubmitPlanRequest request, PlanRow plan, LocalDateTime now) {
        repo.insertPlan(plan);
        int order = 0;
        for (SubmitPlanRequest.NodeSpec node : request.nodes()) {
            long delay = node.launchDelayMs() == null ? 0L : Math.max(0L, node.launchDelayMs());
            boolean healthFail = node.healthCheck() != null && node.healthCheck().fail();
            String healthMessage = node.healthCheck() == null ? null : node.healthCheck().message();
            NodeRow row = new NodeRow(null, request.planId(), node.nodeId(), node.nodeName(),
                    order++, delay, node.simulateStartFailure(), healthFail, healthMessage,
                    node.compensation() == null ? null : node.compensation().action(),
                    node.compensation() != null && node.compensation().simulateFail(),
                    NodeState.PENDING.name(), false, null, null, null, null);
            repo.insertNode(row);

            if (node.parameters() != null) {
                for (Map.Entry<String, String> p : node.parameters().entrySet()) {
                    repo.insertParam(new ParamRow(request.planId(), node.nodeId(),
                            p.getKey(), p.getValue()));
                }
            }
            if (node.dependsOn() != null) {
                for (String dep : new LinkedHashSet<>(node.dependsOn())) {
                    repo.insertDependency(new DependencyRow(
                            request.planId(), node.nodeId(), dep, "EXPLICIT"));
                }
            }
            if (node.condition() != null) {
                SubmitPlanRequest.ConditionSpec c = node.condition();
                repo.insertCondition(new ConditionRow(request.planId(), node.nodeId(),
                        c.predicate(), c.referenceNode(), c.parameterName(), c.expectedValue()));
                // 显式依赖中已包含的引用不重复登记
                boolean alreadyExplicit = node.dependsOn() != null
                        && node.dependsOn().contains(c.referenceNode());
                if (!alreadyExplicit) {
                    repo.insertDependency(new DependencyRow(request.planId(), node.nodeId(),
                            c.referenceNode(), "CONDITION"));
                }
            }
        }
    }

    // ---------- 响应组装 ----------

    private PlanResponse toResponse(PlanRow plan) {
        List<NodeRow> nodes = repo.findNodesByPlanId(plan.planId());
        List<DependencyRow> deps = repo.findDependenciesByPlanId(plan.planId());
        Map<String, List<String>> explicitDeps = new LinkedHashMap<>();
        for (NodeRow n : nodes) {
            explicitDeps.put(n.nodeId(), new ArrayList<>());
        }
        for (DependencyRow d : deps) {
            if ("EXPLICIT".equals(d.depKind())) {
                explicitDeps.computeIfAbsent(d.nodeId(), k -> new ArrayList<>()).add(d.dependsOn());
            }
        }

        List<PlanResponse.NodeView> nodeViews = nodes.stream()
                .map(n -> new PlanResponse.NodeView(n.nodeId(), n.nodeName(), n.state(),
                        n.healthStatus(), n.compensated(), n.failureReason(),
                        explicitDeps.getOrDefault(n.nodeId(), List.of())))
                .toList();

        List<PlanResponse.EventView> eventViews = repo.findEventsByPlanId(plan.planId()).stream()
                .map(e -> new PlanResponse.EventView(e.id(), e.nodeId(), e.eventType(),
                        e.detail(), e.eventTime().toString()))
                .toList();

        return new PlanResponse(plan.planId(), plan.version(), plan.requestId(),
                plan.status(), plan.cancelRequested(), nodeViews, eventViews);
    }

    private PlanRow requirePlan(String planId) {
        PlanRow plan = repo.findPlanByPlanId(planId);
        if (plan == null) {
            throw new NotFoundException("plan not found: " + planId);
        }
        return plan;
    }

    /**
     * 计算提交负载的规范化 SHA-256：仅包含 planId/version 与按 nodeId 排序的节点定义，
     * 不含 requestId（同一负载换 requestId 不影响结构比较；requestId 维度由唯一约束区分）。
     */
    private String canonicalHash(SubmitPlanRequest request) {
        Map<String, Object> canonical = new TreeMap<>();
        canonical.put("planId", request.planId());
        canonical.put("version", request.version());

        Map<String, Object> nodeMap = new TreeMap<>();
        for (SubmitPlanRequest.NodeSpec node : request.nodes()) {
            Map<String, Object> n = new TreeMap<>();
            n.put("nodeName", nullSafe(node.nodeName()));
            n.put("launchDelayMs", node.launchDelayMs() == null ? 0L : node.launchDelayMs());
            n.put("simulateStartFailure", node.simulateStartFailure());
            n.put("parameters", node.parameters() == null
                    ? Map.of() : new TreeMap<>(node.parameters()));
            n.put("dependsOn", node.dependsOn() == null
                    ? List.of() : node.dependsOn().stream().sorted().toList());
            if (node.condition() != null) {
                SubmitPlanRequest.ConditionSpec c = node.condition();
                Map<String, Object> cm = new TreeMap<>();
                cm.put("predicate", c.predicate());
                cm.put("referenceNode", c.referenceNode());
                cm.put("parameterName", nullSafe(c.parameterName()));
                cm.put("expectedValue", nullSafe(c.expectedValue()));
                n.put("condition", cm);
            }
            if (node.healthCheck() != null) {
                Map<String, Object> hm = new TreeMap<>();
                hm.put("fail", node.healthCheck().fail());
                hm.put("message", nullSafe(node.healthCheck().message()));
                n.put("healthCheck", hm);
            }
            if (node.compensation() != null) {
                Map<String, Object> rm = new TreeMap<>();
                rm.put("action", nullSafe(node.compensation().action()));
                rm.put("simulateFail", node.compensation().simulateFail());
                n.put("compensation", rm);
            }
            nodeMap.put(node.nodeId(), n);
        }
        canonical.put("nodes", nodeMap);

        try {
            String json = objectMapper.writeValueAsString(canonical);
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(json.getBytes(StandardCharsets.UTF_8)));
        } catch (JsonProcessingException | NoSuchAlgorithmException e) {
            throw new IllegalStateException("failed to compute payload hash", e);
        }
    }

    private static String nullSafe(String value) {
        return value == null ? "" : value;
    }
}
