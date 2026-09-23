package com.example.starter.rosconfig.service;

import com.example.starter.rosconfig.adapter.NodeAdapter;
import com.example.starter.rosconfig.dto.ChangeSetRequest;
import com.example.starter.rosconfig.dto.NodeResult;
import com.example.starter.rosconfig.dto.NodeResultStatus;
import com.example.starter.rosconfig.dto.TransactionRequest;
import com.example.starter.rosconfig.dto.TransactionResponse;
import com.example.starter.rosconfig.exception.BadRequestException;
import com.example.starter.rosconfig.exception.ConflictException;
import com.example.starter.rosconfig.model.ParameterSchema;
import com.example.starter.rosconfig.repo.ConfigRepository;
import com.example.starter.rosconfig.repo.EventRepository;
import com.example.starter.rosconfig.repo.SchemaRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 事务式热更新编排：幂等校验 -> 静态参数保护 -> 版本检查 -> 候选快照 Schema 校验
 * -> 全节点校验（带时限）-> 原子提交（版本 + 已提交值 + 单条事务事件）。
 *
 * <p>任一节点拒绝、版本冲突或在配置时限内无响应时，数据库值、节点已应用值和事件流都保持原状。</p>
 */
@Service
public class TransactionService {

    private final NodeAdapter nodeAdapter;
    private final SchemaRepository schemaRepository;
    private final ConfigRepository configRepository;
    private final EventRepository eventRepository;
    private final SchemaValidator schemaValidator;
    private final SnapshotMerger snapshotMerger;
    private final JsonValues jsonValues;
    private final ConfigCommitService commitService;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final long validationTimeoutMs;

    public TransactionService(NodeAdapter nodeAdapter,
                              SchemaRepository schemaRepository,
                              ConfigRepository configRepository,
                              EventRepository eventRepository,
                              SchemaValidator schemaValidator,
                              SnapshotMerger snapshotMerger,
                              JsonValues jsonValues,
                              ConfigCommitService commitService,
                              ObjectMapper objectMapper,
                              Clock clock,
                              @Value("${ros.node.validation-timeout-ms:1500}") long validationTimeoutMs) {
        this.nodeAdapter = nodeAdapter;
        this.schemaRepository = schemaRepository;
        this.configRepository = configRepository;
        this.eventRepository = eventRepository;
        this.schemaValidator = schemaValidator;
        this.snapshotMerger = snapshotMerger;
        this.jsonValues = jsonValues;
        this.commitService = commitService;
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.validationTimeoutMs = validationTimeoutMs;
    }

    /**
     * 执行一笔跨节点热更新事务。同键同参返回原结果；同键异参抛出 {@link ConflictException}（409）。
     */
    public TransactionResponse execute(TransactionRequest request) {
        String transactionId = request.transactionId();
        String requestHash = hashRequest(request);

        EventRepository.TransactionRecord existing = eventRepository.findRecord(transactionId);
        if (existing != null) {
            return resolveExisting(existing, requestHash);
        }

        long now = clock.millis();
        if (!commitService.claim(transactionId, requestHash, now)) {
            EventRepository.TransactionRecord raced = waitForTerminal(transactionId);
            if (raced == null) {
                throw new ConflictException("transactionId " + transactionId
                        + " is still being processed");
            }
            return resolveExisting(raced, requestHash);
        }

        try {
            return process(request);
        } catch (ConflictException e) {
            throw e;
        } catch (RuntimeException e) {
            // 非预期异常：释放幂等占位，允许调用方使用同一事务键重试
            eventRepository.deleteRecord(transactionId);
            throw e;
        }
    }

    /** 已存在幂等记录：同参返回原结果（静态参数失败重放 409）；异参 409。 */
    private TransactionResponse resolveExisting(EventRepository.TransactionRecord record, String requestHash) {
        if (!record.requestHash().equals(requestHash)) {
            throw new ConflictException("transactionId " + record.transactionId()
                    + " already used with different parameters");
        }
        if (EventRepository.STATUS_PROCESSING.equals(record.status())) {
            throw new ConflictException("transactionId " + record.transactionId()
                    + " is still being processed");
        }
        TransactionResponse replay = parseResponse(record.responseText());
        replay.results().stream()
                .filter(r -> r.status() == NodeResultStatus.STATIC_PARAM)
                .findFirst()
                .ifPresent(r -> {
                    throw new ConflictException(r.message());
                });
        return replay;
    }

    /** 并发同键请求等待首笔处理到达终态；异参立即按 409 处理由调用方判定。 */
    private EventRepository.TransactionRecord waitForTerminal(String transactionId) {
        long deadline = System.currentTimeMillis() + 10_000L;
        EventRepository.TransactionRecord record;
        do {
            record = eventRepository.findRecord(transactionId);
            if (record != null && !EventRepository.STATUS_PROCESSING.equals(record.status())) {
                return record;
            }
            try {
                Thread.sleep(20L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }
        } while (System.currentTimeMillis() < deadline);
        return null;
    }

    private TransactionResponse process(TransactionRequest request) {
        String transactionId = request.transactionId();
        String requestHash = hashRequest(request);

        List<NodeResult> results = new ArrayList<>();
        Map<String, List<ParameterSchema>> schemasByNode = new LinkedHashMap<>();
        Map<String, Long> expectedVersions = new LinkedHashMap<>();
        Map<String, Map<String, Object>> candidates = new LinkedHashMap<>();
        boolean localFailure = false;

        for (ChangeSetRequest change : request.changes()) {
            String node = change.node();
            List<ParameterSchema> schemas = schemaRepository.findByNode(node);
            if (schemas.isEmpty()) {
                results.add(NodeResult.of(node, NodeResultStatus.SCHEMA_REJECTED,
                        "unknown node: " + node, null, null));
                localFailure = true;
                continue;
            }
            schemasByNode.put(node, schemas);

            Map<String, ParameterSchema> byName = new LinkedHashMap<>();
            for (ParameterSchema schema : schemas) {
                byName.put(schema.name(), schema);
            }
            List<String> staticParams = new ArrayList<>();
            for (String paramName : change.params().keySet()) {
                ParameterSchema schema = byName.get(paramName);
                if (schema != null && !schema.dynamic()) {
                    staticParams.add(paramName);
                }
            }
            if (!staticParams.isEmpty()) {
                String message = "static parameters cannot be hot-updated: "
                        + String.join(", ", staticParams)
                        + "; use pending restart config instead";
                results.add(NodeResult.of(node, NodeResultStatus.STATIC_PARAM, message, null, null));
                localFailure = true;
                continue;
            }
            long currentVersion = configRepository.getOrInitVersion(node);
            if (change.expectedVersion() != currentVersion) {
                results.add(NodeResult.of(node, NodeResultStatus.VERSION_CONFLICT,
                        "expected version " + change.expectedVersion()
                                + " but current is " + currentVersion,
                        currentVersion, null));
                localFailure = true;
                continue;
            }
            expectedVersions.put(node, currentVersion);

            Map<String, Object> candidate;
            try {
                candidate = snapshotMerger.merge(schemas, node, change.params());
            } catch (IllegalArgumentException e) {
                results.add(NodeResult.of(node, NodeResultStatus.SCHEMA_REJECTED,
                        e.getMessage(), currentVersion, null));
                localFailure = true;
                continue;
            }
            String violation = schemaValidator.validate(schemas, candidate);
            if (violation != null) {
                results.add(NodeResult.of(node, NodeResultStatus.SCHEMA_REJECTED,
                        violation, currentVersion, null));
                localFailure = true;
                continue;
            }
            candidates.put(node, candidate);
        }

        if (localFailure) {
            for (ChangeSetRequest change : request.changes()) {
                String node = change.node();
                boolean covered = results.stream().anyMatch(r -> r.node().equals(node));
                if (!covered) {
                    results.add(NodeResult.of(node, NodeResultStatus.SKIPPED,
                            "rolled back due to other node failure",
                            expectedVersions.get(node), null));
                }
            }
            TransactionResponse response = new TransactionResponse(transactionId, false, null,
                    Map.of(), List.copyOf(results));
            commitService.recordFailure(transactionId, requestHash, toJson(response), clock.millis());
            // 静态参数出现在热更新变更集中：按契约返回 409 并指出参数名（失败结果同样幂等重放）
            response.results().stream()
                    .filter(r -> r.status() == NodeResultStatus.STATIC_PARAM)
                    .findFirst()
                    .ifPresent(r -> {
                        throw new ConflictException(r.message());
                    });
            return response;
        }

        // 阶段二：全部目标节点并行校验候选快照，任一拒绝/超时则整笔回滚
        Map<String, NodeResult> nodeOutcomes = validateNodes(candidates, expectedVersions);
        boolean allAccepted = nodeOutcomes.values().stream()
                .allMatch(r -> r.status() == NodeResultStatus.ACCEPTED);
        if (!allAccepted) {
            TransactionResponse response = new TransactionResponse(transactionId, false, null,
                    Map.of(), List.copyOf(nodeOutcomes.values()));
            commitService.recordFailure(transactionId, requestHash, toJson(response), clock.millis());
            return response;
        }

        // 阶段三：原子提交（版本 + 已提交值 + 唯一事务事件 + 幂等记录终态）
        // 落库值取自候选快照中已按 Schema 类型规范化的值，而非请求原始 JSON 值
        Map<String, Map<String, String>> changedValueTexts = new LinkedHashMap<>();
        for (ChangeSetRequest change : request.changes()) {
            Map<String, String> texts = new LinkedHashMap<>();
            Map<String, Object> candidate = candidates.get(change.node());
            for (Map.Entry<String, Object> entry : change.params().entrySet()) {
                Object value = entry.getValue() == null ? null : candidate.get(entry.getKey());
                texts.put(entry.getKey(), jsonValues.write(value));
            }
            changedValueTexts.put(change.node(), texts);
        }
        String eventPayload = toJson(Map.of(
                "transactionId", transactionId,
                "changes", request.changes()));

        long now = clock.millis();
        ConfigCommitService.CommitOutcome outcome;
        try {
            outcome = commitService.commit(transactionId, expectedVersions, changedValueTexts,
                    eventPayload, requestHash, now, (cursor, newVersions) -> {
                        List<NodeResult> committedResults = new ArrayList<>();
                        for (Map.Entry<String, Long> entry : expectedVersions.entrySet()) {
                            committedResults.add(NodeResult.of(entry.getKey(),
                                    NodeResultStatus.ACCEPTED, null,
                                    entry.getValue(), newVersions.get(entry.getKey())));
                        }
                        return toJson(new TransactionResponse(transactionId, true, cursor,
                                newVersions, committedResults));
                    });
        } catch (ConfigCommitService.VersionConflictAtCommitException e) {
            List<NodeResult> conflictResults = new ArrayList<>();
            for (Map.Entry<String, Long> entry : expectedVersions.entrySet()) {
                if (entry.getKey().equals(e.node())) {
                    conflictResults.add(NodeResult.of(entry.getKey(),
                            NodeResultStatus.VERSION_CONFLICT, e.getMessage(),
                            entry.getValue(), null));
                } else {
                    conflictResults.add(NodeResult.of(entry.getKey(),
                            NodeResultStatus.SKIPPED, "rolled back due to other node failure",
                            entry.getValue(), null));
                }
            }
            TransactionResponse response = new TransactionResponse(transactionId, false, null,
                    Map.of(), conflictResults);
            commitService.recordFailure(transactionId, requestHash, toJson(response), clock.millis());
            return response;
        }

        // 提交成功后应用各节点（数据库已原子提交，节点应用失败不影响一致性语义）
        for (Map.Entry<String, Map<String, Object>> entry : candidates.entrySet()) {
            nodeAdapter.apply(entry.getKey(), entry.getValue());
        }

        EventRepository.TransactionRecord record = eventRepository.findRecord(transactionId);
        return parseResponse(record.responseText());
    }

    /**
     * 并行调用各目标节点校验候选快照；任一节点在配置时限内无响应视为超时。
     */
    private Map<String, NodeResult> validateNodes(Map<String, Map<String, Object>> candidates,
                                                  Map<String, Long> expectedVersions) {
        Map<String, NodeResult> outcomes = new LinkedHashMap<>();
        ExecutorService pool = Executors.newFixedThreadPool(Math.max(1, candidates.size()));
        try {
            Map<String, Future<NodeResult>> futures = new LinkedHashMap<>();
            for (Map.Entry<String, Map<String, Object>> entry : candidates.entrySet()) {
                String node = entry.getKey();
                Map<String, Object> candidate = entry.getValue();
                Callable<NodeResult> task = () -> {
                    boolean accepted = nodeAdapter.validate(node, candidate);
                    if (accepted) {
                        return NodeResult.of(node, NodeResultStatus.ACCEPTED, null,
                                expectedVersions.get(node), null);
                    }
                    String reason = nodeAdapter.lastRejection(node);
                    return NodeResult.of(node, NodeResultStatus.REJECTED,
                            reason == null ? "node rejected candidate config" : reason,
                            expectedVersions.get(node), null);
                };
                futures.put(node, pool.submit(task));
            }
            for (Map.Entry<String, Future<NodeResult>> entry : futures.entrySet()) {
                String node = entry.getKey();
                try {
                    outcomes.put(node, entry.getValue().get(validationTimeoutMs, TimeUnit.MILLISECONDS));
                } catch (TimeoutException e) {
                    entry.getValue().cancel(true);
                    outcomes.put(node, NodeResult.of(node, NodeResultStatus.TIMEOUT,
                            "node did not respond within " + validationTimeoutMs + " ms",
                            expectedVersions.get(node), null));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    outcomes.put(node, NodeResult.of(node, NodeResultStatus.TIMEOUT,
                            "validation interrupted", expectedVersions.get(node), null));
                } catch (ExecutionException e) {
                    outcomes.put(node, NodeResult.of(node, NodeResultStatus.REJECTED,
                            "node validation error: " + e.getCause().getMessage(),
                            expectedVersions.get(node), null));
                }
            }
        } finally {
            pool.shutdownNow();
        }
        return outcomes;
    }

    /** 规范化请求哈希：节点按名排序、参数按键排序后序列化再取 SHA-256。 */
    private String hashRequest(TransactionRequest request) {
        Map<String, Object> canonical = new TreeMap<>();
        for (ChangeSetRequest change : request.changes()) {
            canonical.put(change.node(), Map.of(
                    "expectedVersion", change.expectedVersion(),
                    "params", new TreeMap<>(change.params())));
        }
        try {
            byte[] json = objectMapper.writeValueAsBytes(canonical);
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(json);
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException | com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new BadRequestException("cannot hash request: " + e.getMessage());
        }
    }

    private TransactionResponse parseResponse(String json) {
        try {
            return objectMapper.readValue(json, TransactionResponse.class);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("stored transaction response is unreadable", e);
        }
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("cannot serialize response", e);
        }
    }
}
