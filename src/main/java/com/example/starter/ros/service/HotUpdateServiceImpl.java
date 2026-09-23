package com.example.starter.ros.service;

import com.example.starter.ros.dto.HotUpdateRequest;
import com.example.starter.ros.dto.HotUpdateResponse;
import com.example.starter.ros.error.ApiConflictException;
import com.example.starter.ros.model.ParamSchema;
import com.example.starter.ros.node.NodeAdapter;
import com.example.starter.ros.node.ValidationOutcome;
import com.example.starter.ros.repo.EventJdbcRepository;
import com.example.starter.ros.repo.ParameterJdbcRepository;
import com.example.starter.ros.repo.PendingRestartJdbcRepository;
import com.example.starter.ros.repo.TransactionJdbcRepository;
import com.example.starter.ros.time.RosClock;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 跨节点事务式热更新核心编排。
 * 流程：幂等检查 -> 静态参数保护 -> 各节点候选快照构建/Schema/依赖校验 ->
 * 版本检查 -> 全部节点限期并行校验 -> 数据库原子提交（各节点版本 + 一条事务事件）-> 节点应用。
 * 任一节点拒绝、版本冲突或超时：不写值、不加版本、不追加事件、不应用节点，返回各节点结果。
 */
@Service
public class HotUpdateServiceImpl implements HotUpdateService {

    private static final Logger log = LoggerFactory.getLogger(HotUpdateServiceImpl.class);

    private final SchemaService schemaService;
    private final CandidateSnapshotValidator snapshotValidator;
    private final ParameterJdbcRepository parameterRepository;
    private final TransactionJdbcRepository transactionRepository;
    private final EventJdbcRepository eventRepository;
    private final PendingRestartJdbcRepository pendingRestartRepository;
    private final NodeAdapter nodeAdapter;
    private final RosClock clock;
    private final ObjectMapper objectMapper;
    private final TransactionCommitter committer;
    private final long defaultTimeoutMs;
    private final ExecutorService validatorPool = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "ros-node-validator");
        t.setDaemon(true);
        return t;
    });

    public HotUpdateServiceImpl(SchemaService schemaService,
                                CandidateSnapshotValidator snapshotValidator,
                                ParameterJdbcRepository parameterRepository,
                                TransactionJdbcRepository transactionRepository,
                                EventJdbcRepository eventRepository,
                                PendingRestartJdbcRepository pendingRestartRepository,
                                NodeAdapter nodeAdapter,
                                RosClock clock,
                                ObjectMapper objectMapper,
                                TransactionCommitter committer,
                                @Value("${ros.node.timeout-ms:2000}") long defaultTimeoutMs) {
        this.schemaService = schemaService;
        this.snapshotValidator = snapshotValidator;
        this.parameterRepository = parameterRepository;
        this.transactionRepository = transactionRepository;
        this.eventRepository = eventRepository;
        this.pendingRestartRepository = pendingRestartRepository;
        this.nodeAdapter = nodeAdapter;
        this.clock = clock;
        this.objectMapper = objectMapper;
        this.committer = committer;
        this.defaultTimeoutMs = defaultTimeoutMs;
    }

    @Override
    public HotUpdateResponse update(HotUpdateRequest request) {
        long timeoutMs = request.nodeTimeoutMs() != null && request.nodeTimeoutMs() > 0
                ? request.nodeTimeoutMs() : defaultTimeoutMs;
        String fingerprint = fingerprint(request);

        // 1. 幂等检查：同键同参返回原结果，同键异参 409
        String existingResponse = transactionRepository.findResponseJson(request.transactionId());
        if (existingResponse != null) {
            return replayOrConflict(request.transactionId(), fingerprint, existingResponse);
        }

        // 2. 请求结构与静态参数保护（任何写入之前）
        validateRequestShape(request);
        List<String> staticViolations = collectStaticParamViolations(request);
        if (!staticViolations.isEmpty()) {
            throw new ApiConflictException(
                    "静态参数不得通过热更新改变，只能创建待重启配置: " + String.join(", ", staticViolations));
        }

        // 3. 逐节点预校验（候选快照、Schema/依赖、版本、节点限期响应）
        Map<String, Map<String, Object>> candidates = new LinkedHashMap<>();
        List<HotUpdateResponse.NodeResult> results = precheckAll(request, timeoutMs, candidates);

        boolean allAccepted = results.stream()
                .allMatch(r -> HotUpdateResponse.NodeResult.ACCEPTED.equals(r.status()));
        if (!allAccepted) {
            // 4. 预校验失败：值、版本、事件流、节点已应用值全部保持原状；仅登记终态用于幂等重放
            return persistTerminalOutcome(request.transactionId(), fingerprint, results, false);
        }

        // 5. 全部接受：数据库原子提交各节点版本与一条事务事件
        CommitOutcome commit;
        try {
            commit = committer.commit(request, fingerprint, candidates, clock.nowMillis());
        } catch (DuplicateKeyException duplicate) {
            // 并发重复事务：对方已落终态，按幂等规则返回原结果或 409
            String concurrent = transactionRepository.findResponseJson(request.transactionId());
            if (concurrent != null) {
                return replayOrConflict(request.transactionId(), fingerprint, concurrent);
            }
            throw new ApiConflictException("事务 " + request.transactionId() + " 与并发事务冲突");
        } catch (CommitVersionConflictException conflict) {
            // 行锁内发现版本已被并发事务推进：本事务整体回滚
            List<HotUpdateResponse.NodeResult> conflictResults = new ArrayList<>();
            for (HotUpdateRequest.NodeChangeSet change : request.changes()) {
                conflictResults.add(new HotUpdateResponse.NodeResult(
                        change.node(), HotUpdateResponse.NodeResult.VERSION_CONFLICT,
                        "节点版本已被并发事务推进，请基于最新版本重试", null));
            }
            return persistTerminalOutcome(request.transactionId(), fingerprint, conflictResults, false);
        }

        // 6. 提交成功后才让节点应用；此前节点已应用值不会被触碰
        for (HotUpdateRequest.NodeChangeSet change : request.changes()) {
            nodeAdapter.apply(change.node(), commit.candidates().get(change.node()));
        }
        return commit.response();
    }

    /**
     * 逐节点构建候选快照并汇总校验结论；节点校验在同一时限内并行进行。
     */
    private List<HotUpdateResponse.NodeResult> precheckAll(
            HotUpdateRequest request, long timeoutMs,
            Map<String, Map<String, Object>> candidatesOut) {
        // 节点 -> 预校验阶段的问题（候选构建、Schema/依赖、版本）
        Map<String, String> localProblems = new LinkedHashMap<>();

        for (HotUpdateRequest.NodeChangeSet change : request.changes()) {
            String node = change.node();
            try {
                Map<String, Object> candidate = schemaService.buildCandidate(node, change.parameters());
                candidatesOut.put(node, candidate);
                List<ParamSchema> nodeSchemas = schemaService.byNode(node);
                if (nodeSchemas.isEmpty()) {
                    localProblems.put(node, "节点未注册任何参数 Schema");
                    continue;
                }
                List<String> violations = snapshotValidator.validate(nodeSchemas, candidate);
                if (!violations.isEmpty()) {
                    localProblems.put(node, String.join("; ", violations));
                    continue;
                }
                long current = parameterRepository.currentVersion(node);
                if (current != change.expectedVersion()) {
                    localProblems.put(node, "期望版本 " + change.expectedVersion() + " 与当前版本 " + current + " 不一致");
                }
            } catch (RuntimeException ex) {
                localProblems.put(node, "候选配置构建失败: " + ex.getMessage());
            }
        }

        // 仅对本地预校验通过的节点发起限期校验，全部并行
        List<String> nodesToValidate = new ArrayList<>();
        Map<String, Future<ValidationOutcome>> futures = new LinkedHashMap<>();
        for (HotUpdateRequest.NodeChangeSet change : request.changes()) {
            String node = change.node();
            if (localProblems.containsKey(node)) {
                continue;
            }
            nodesToValidate.add(node);
            Map<String, Object> candidate = candidatesOut.get(node);
            futures.put(node, validatorPool.submit(() -> nodeAdapter.validate(node, candidate, timeoutMs)));
        }

        Map<String, ValidationOutcome> outcomes = new LinkedHashMap<>();
        for (String node : nodesToValidate) {
            try {
                outcomes.put(node, futures.get(node).get(timeoutMs, TimeUnit.MILLISECONDS));
            } catch (TimeoutException timeout) {
                futures.get(node).cancel(true);
                outcomes.put(node, new ValidationOutcome(false, "节点在 " + timeoutMs + "ms 时限内无响应"));
                log.warn("节点 {} 校验超时（{}ms），事务将整体不提交", node, timeoutMs);
            } catch (Exception e) {
                futures.get(node).cancel(true);
                outcomes.put(node, new ValidationOutcome(false, "节点校验异常: " + rootMessage(e)));
            }
        }

        List<HotUpdateResponse.NodeResult> results = new ArrayList<>();
        for (HotUpdateRequest.NodeChangeSet change : request.changes()) {
            String node = change.node();
            String localProblem = localProblems.get(node);
            if (localProblem != null) {
                String status = localProblem.startsWith("期望版本")
                        ? HotUpdateResponse.NodeResult.VERSION_CONFLICT
                        : HotUpdateResponse.NodeResult.REJECTED;
                results.add(new HotUpdateResponse.NodeResult(node, status, localProblem, null));
                continue;
            }
            ValidationOutcome outcome = outcomes.get(node);
            if (outcome != null && outcome.accepted()) {
                results.add(new HotUpdateResponse.NodeResult(
                        node, HotUpdateResponse.NodeResult.ACCEPTED, null, change.expectedVersion()));
            } else {
                String reason = outcome == null ? "节点无响应" : outcome.reason();
                String status = reason != null && reason.contains("时限内无响应")
                        ? HotUpdateResponse.NodeResult.TIMEOUT
                        : HotUpdateResponse.NodeResult.REJECTED;
                results.add(new HotUpdateResponse.NodeResult(node, status, reason, null));
            }
        }
        return results;
    }

    private HotUpdateResponse persistTerminalOutcome(String transactionId, String fingerprint,
                                                     List<HotUpdateResponse.NodeResult> results,
                                                     boolean committed) {
        HotUpdateResponse response = new HotUpdateResponse(transactionId, committed, false, results);
        String responseJson = writeResponse(response);
        boolean inserted;
        try {
            inserted = transactionRepository.insertIfAbsent(
                    transactionId, committed, fingerprint, responseJson, clock.nowMillis());
        } catch (DuplicateKeyException concurrent) {
            inserted = false;
        }
        if (!inserted) {
            String existing = transactionRepository.findResponseJson(transactionId);
            if (existing != null) {
                return replayOrConflict(transactionId, fingerprint, existing);
            }
            throw new ApiConflictException("事务 " + transactionId + " 与并发事务冲突");
        }
        return response;
    }

    private HotUpdateResponse replayOrConflict(String transactionId, String fingerprint, String existingJson) {
        if (!fingerprint.equals(transactionRepository.findFingerprint(transactionId))) {
            throw new ApiConflictException("事务 ID " + transactionId + " 已用于不同参数的请求");
        }
        try {
            HotUpdateResponse original = objectMapper.readValue(existingJson, HotUpdateResponse.class);
            return new HotUpdateResponse(original.transactionId(), original.committed(), true,
                    original.nodeResults());
        } catch (Exception e) {
            throw new IllegalStateException("读取已存事务结果失败", e);
        }
    }

    private String writeResponse(HotUpdateResponse response) {
        try {
            return objectMapper.writeValueAsString(response);
        } catch (Exception e) {
            throw new IllegalStateException("序列化事务结果失败", e);
        }
    }

    private void validateRequestShape(HotUpdateRequest request) {
        Set<String> seen = new HashSet<>();
        for (HotUpdateRequest.NodeChangeSet change : request.changes()) {
            if (!seen.add(change.node())) {
                throw new IllegalArgumentException("同一事务中节点出现多次: " + change.node());
            }
        }
    }

    private List<String> collectStaticParamViolations(HotUpdateRequest request) {
        List<String> violations = new ArrayList<>();
        for (HotUpdateRequest.NodeChangeSet change : request.changes()) {
            if (change.parameters() == null) {
                continue;
            }
            change.parameters().forEach((group, params) -> {
                if (params == null) {
                    return;
                }
                params.forEach((name, raw) -> {
                    ParamSchema schema = schemaService.find(change.node(), group, name);
                    if (schema == null) {
                        throw new IllegalArgumentException(
                                "节点 " + change.node() + " 不存在参数 Schema: " + group + "." + name);
                    }
                    // 类型合法性在候选快照构建阶段统一校验并汇总为节点结果；此处只判动态/静态
                    if (!schema.dynamic()) {
                        violations.add(change.node() + ":" + group + "." + name);
                    }
                });
            });
        }
        return violations;
    }

    /**
     * 请求指纹：只包含节点、期望版本与变更参数（键排序），不含事务 ID 与超时设置。
     */
    private String fingerprint(HotUpdateRequest request) {
        List<Map<String, Object>> normalized = new ArrayList<>();
        for (HotUpdateRequest.NodeChangeSet change : request.changes()) {
            Map<String, Object> nodeEntry = new LinkedHashMap<>();
            nodeEntry.put("node", change.node());
            nodeEntry.put("expectedVersion", change.expectedVersion());
            nodeEntry.put("parameters", normalize(change.parameters()));
            normalized.add(nodeEntry);
        }
        try {
            return objectMapper.writeValueAsString(normalized);
        } catch (Exception e) {
            throw new IllegalArgumentException("请求参数无法序列化为指纹", e);
        }
    }

    @SuppressWarnings("unchecked")
    private Object normalize(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> sorted = new java.util.TreeMap<>();
            map.forEach((k, v) -> sorted.put(String.valueOf(k), normalize(v)));
            return sorted;
        }
        if (value instanceof List<?> list) {
            List<Object> items = new ArrayList<>(list.size());
            list.forEach(item -> items.add(normalize(item)));
            return items;
        }
        return value;
    }

    private static String rootMessage(Throwable t) {
        Throwable cur = t;
        while (cur.getCause() != null && cur.getCause() != cur) {
            cur = cur.getCause();
        }
        return cur.getMessage() == null ? cur.getClass().getSimpleName() : cur.getMessage();
    }

    /** 原子提交输出：响应与各节点候选快照（供提交后节点应用）。 */
    record CommitOutcome(HotUpdateResponse response,
                         Map<String, Map<String, Object>> candidates) {
    }

    /** 行锁内发现版本冲突时抛出，驱动整笔提交回滚。 */
    static class CommitVersionConflictException extends RuntimeException {
        CommitVersionConflictException(String message) {
            super(message);
        }
    }
}
