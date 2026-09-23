package com.example.starter.ros;

import com.example.starter.ros.adapter.NodeAdapter;
import com.example.starter.ros.db.EventRepository;
import com.example.starter.ros.db.NodeStateRepository;
import com.example.starter.ros.db.TransactionRecordRepository;
import com.example.starter.ros.support.ParamValues;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.example.starter.ros.db.SchemaRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 事务式配置热更新应用服务。
 *
 * <p>流程：幂等判定 → 构建候选快照（合并默认值）→ 静态参数保护（409）
 * → 版本/必填/依赖本地校验 → 全部目标节点并发、有界超时校验 →
 * 任一失败则只记录事务结果（值、版本、节点应用值、事件流均不变）；
 * 全部接受后在单个数据库事务内锁行复检版本、原子推进各节点版本并写入一条事务事件。
 */
@Service
public class TransactionService {

    private static final Logger log = LoggerFactory.getLogger(TransactionService.class);
    private static final String COMMIT_EVENT = "PARAMETERS_COMMITTED";

    private final SchemaRepository schemaRepository;
    private final NodeStateRepository nodeStateRepository;
    private final TransactionRecordRepository transactionRecordRepository;
    private final EventRepository eventRepository;
    private final ParameterQueryService parameterQueryService;
    private final CandidateValidator candidateValidator;
    private final NodeAdapter nodeAdapter;
    private final ExecutorService nodeExecutor;
    private final Clock clock;
    private final TransactionTemplate transactionTemplate;
    private final ObjectMapper canonicalMapper;
    private final ObjectMapper responseMapper;
    private final long timeoutMillis;

    public TransactionService(SchemaRepository schemaRepository,
                              NodeStateRepository nodeStateRepository,
                              TransactionRecordRepository transactionRecordRepository,
                              EventRepository eventRepository,
                              ParameterQueryService parameterQueryService,
                              CandidateValidator candidateValidator,
                              NodeAdapter nodeAdapter,
                              ExecutorService rosNodeExecutor,
                              Clock rosClock,
                              PlatformTransactionManager transactionManager,
                              @Value("${ros.transaction.timeout-ms:2000}") long timeoutMillis) {
        this.schemaRepository = schemaRepository;
        this.nodeStateRepository = nodeStateRepository;
        this.transactionRecordRepository = transactionRecordRepository;
        this.eventRepository = eventRepository;
        this.parameterQueryService = parameterQueryService;
        this.candidateValidator = candidateValidator;
        this.nodeAdapter = nodeAdapter;
        this.nodeExecutor = rosNodeExecutor;
        this.clock = rosClock;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.timeoutMillis = timeoutMillis;
        this.canonicalMapper = new ObjectMapper()
                .configure(com.fasterxml.jackson.databind.SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);
        this.responseMapper = new ObjectMapper()
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }

    /**
     * 执行热更新事务。数据库工作在单个事务中完成；节点“应用”仅在数据库提交成功后执行。
     */
    public TransactionResponse execute(TransactionRequest request) {
        String requestHash = canonicalHash(request);

        // 幂等：同键同参返回原结果；同键异参 409
        var existing = transactionRecordRepository.find(request.transactionId());
        if (existing.isPresent()) {
            return replayOrConflict(request.transactionId(), requestHash, existing.get());
        }

        ProcessResult result;
        try {
            result = transactionTemplate.execute(status -> processInTransaction(request, requestHash));
        } catch (DataIntegrityViolationException duplicate) {
            // 并发提交同一 transactionId：唯一约束胜者已落库，转为重放或冲突
            var winner = transactionRecordRepository.find(request.transactionId());
            if (winner.isPresent()) {
                return replayOrConflict(request.transactionId(), requestHash, winner.get());
            }
            throw duplicate;
        }

        if (result.response().status() == TransactionStatus.COMMITTED && !result.response().replayed()) {
            for (NodePrepared prepared : result.toApply()) {
                nodeAdapter.apply(prepared.nodeId(), prepared.candidate(),
                        prepared.currentVersion() + 1);
            }
        }
        return result.response();
    }

    private ProcessResult processInTransaction(TransactionRequest request, String requestHash) {
        // 再次检查（同事务内防御并发重入走到写库阶段）
        var existing = transactionRecordRepository.find(request.transactionId());
        if (existing.isPresent()) {
            TransactionResponse replayed = replayOrConflict(
                    request.transactionId(), requestHash, existing.get());
            return new ProcessResult(replayed, List.of());
        }

        List<String> nodeIds = new ArrayList<>(request.nodes().keySet());
        for (String nodeId : nodeIds) {
            nodeStateRepository.initIfAbsent(nodeId);
        }

        // 阶段 1：构建每个节点的候选快照（未知参数/类型错误直接 400）
        Map<String, NodePrepared> preparedByNode = new LinkedHashMap<>();
        for (String nodeId : nodeIds) {
            NodeChange change = request.nodes().get(nodeId);
            Map<String, Object> changes = change.values() == null ? Map.of() : change.values();
            long currentVersion = nodeStateRepository.currentVersion(nodeId);
            Map<String, Object> candidate = parameterQueryService.buildCandidate(nodeId, changes);
            List<ParameterSchema> schemas = schemaRepository.findByNode(nodeId);
            if (schemas.isEmpty()) {
                throw new SchemaValidationException(List.of(
                        "node '" + nodeId + "' has no parameter schema registered"));
            }
            Map<String, String> changedText = new LinkedHashMap<>();
            for (String key : changes.keySet()) {
                // 变更值为 null 表示重置为默认值：删除显式提交行使默认值重新生效
                changedText.put(key, changes.get(key) == null
                        ? null : ParamValues.format(candidate.get(key)));
            }
            preparedByNode.put(nodeId, new NodePrepared(
                    nodeId, change.expectedVersion(), currentVersion, candidate, changedText));
        }

        // 阶段 2：静态参数保护——任何实际改变静态参数的热更新一律 409 并指出参数名
        List<String> staticViolations = new ArrayList<>();
        for (NodePrepared prepared : preparedByNode.values()) {
            Map<String, Object> changes = request.nodes().get(prepared.nodeId()).values();
            if (changes == null) {
                changes = Map.of();
            }
            List<String> changedStatic = candidateValidator.changedStaticKeys(
                    schemaRepository.findByNode(prepared.nodeId()),
                    parameterQueryService.effectiveValues(prepared.nodeId()),
                    changes, prepared.candidate());
            for (String key : changedStatic) {
                staticViolations.add("static parameter '" + key + "' on node '"
                        + prepared.nodeId() + "' cannot be changed by hot update; "
                        + "create a pending-reboot configuration instead");
            }
        }
        if (!staticViolations.isEmpty()) {
            throw new ConflictException(String.join("; ", staticViolations));
        }

        // 阶段 3：本地校验——版本冲突、必填缺失、依赖冲突（依赖基于完整候选快照）
        Map<String, NodeOutcome> outcomes = new LinkedHashMap<>();
        Map<String, NodePrepared> eligibleForNodeCheck = new LinkedHashMap<>();
        for (NodePrepared prepared : preparedByNode.values()) {
            if (!prepared.versionMatches()) {
                outcomes.put(prepared.nodeId(), NodeOutcome.versionConflict(
                        prepared.expectedVersion(), prepared.currentVersion()));
                continue;
            }
            List<ParameterSchema> schemas = schemaRepository.findByNode(prepared.nodeId());
            List<String> errors = candidateValidator.validate(
                    prepared.nodeId(), schemas, prepared.candidate());
            if (!errors.isEmpty()) {
                outcomes.put(prepared.nodeId(), NodeOutcome.rejected(
                        prepared.expectedVersion(), prepared.currentVersion(),
                        String.join("; ", errors)));
                continue;
            }
            eligibleForNodeCheck.put(prepared.nodeId(), prepared);
        }

        // 阶段 4：全部通过本地校验的节点并发请求节点适配器，单节点有界等待
        Map<String, NodeCheck> checks = validateAtNodes(eligibleForNodeCheck);
        for (Map.Entry<String, NodeCheck> entry : checks.entrySet()) {
            NodePrepared prepared = eligibleForNodeCheck.get(entry.getKey());
            NodeCheck check = entry.getValue();
            NodeOutcome outcome = switch (check.kind()) {
                case OK -> NodeOutcome.accepted(
                        prepared.expectedVersion(), prepared.currentVersion());
                case REJECTED -> NodeOutcome.rejected(
                        prepared.expectedVersion(), prepared.currentVersion(), check.message());
                case TIMEOUT -> NodeOutcome.timeout(
                        prepared.expectedVersion(), prepared.currentVersion());
            };
            outcomes.put(entry.getKey(), outcome);
        }
        // 保持与请求一致的节点顺序
        Map<String, NodeOutcome> orderedOutcomes = new LinkedHashMap<>();
        for (String nodeId : nodeIds) {
            orderedOutcomes.put(nodeId, outcomes.get(nodeId));
        }

        boolean allAccepted = orderedOutcomes.values().stream()
                .allMatch(o -> o.status() == OutcomeStatus.ACCEPTED);

        if (!allAccepted) {
            // 回滚：不推进版本、不写参数、不产生事件；仅留幂等记录，返回各节点结果
            TransactionResponse response = finishTransaction(
                    request, requestHash, TransactionStatus.ROLLED_BACK, orderedOutcomes, null);
            return new ProcessResult(response, List.of());
        }

        // 阶段 5：提交前锁行复检版本，防止两个事务并发通过 expectedVersion 检查
        for (NodePrepared prepared : preparedByNode.values()) {
            long lockedVersion = nodeStateRepository.currentVersionForUpdate(prepared.nodeId());
            if (lockedVersion != prepared.currentVersion()) {
                Map<String, NodeOutcome> conflictOutcomes = new LinkedHashMap<>();
                for (NodePrepared other : preparedByNode.values()) {
                    conflictOutcomes.put(other.nodeId(), NodeOutcome.versionConflict(
                            other.expectedVersion(),
                            other.nodeId().equals(prepared.nodeId()) ? lockedVersion : other.currentVersion()));
                }
                TransactionResponse response = finishTransaction(
                        request, requestHash, TransactionStatus.ROLLED_BACK, conflictOutcomes, null);
                return new ProcessResult(response, List.of());
            }
        }

        // 阶段 6：原子提交——各节点版本 + 变更写入，整条事务只产生一条事件
        List<Map<String, Object>> eventNodes = new ArrayList<>();
        for (NodePrepared prepared : preparedByNode.values()) {
            long newVersion = prepared.currentVersion() + 1;
            nodeStateRepository.applyChanges(prepared.nodeId(), newVersion, prepared.changedText());
            eventNodes.add(Map.of("nodeId", prepared.nodeId(), "version", newVersion));
        }
        String payloadJson = writeJson(Map.of(
                "transactionId", request.transactionId(),
                "nodes", eventNodes));
        long eventCursor = eventRepository.insert(
                request.transactionId(), COMMIT_EVENT, payloadJson);

        Map<String, NodeOutcome> committedOutcomes = new LinkedHashMap<>();
        for (NodePrepared prepared : preparedByNode.values()) {
            committedOutcomes.put(prepared.nodeId(), NodeOutcome.accepted(
                    prepared.expectedVersion(), prepared.currentVersion() + 1));
        }
        TransactionResponse response = finishTransaction(
                request, requestHash, TransactionStatus.COMMITTED, committedOutcomes, eventCursor);
        return new ProcessResult(response, new ArrayList<>(preparedByNode.values()));
    }

    private Map<String, NodeCheck> validateAtNodes(Map<String, NodePrepared> eligible) {
        Map<String, Future<NodeCheck>> futures = new LinkedHashMap<>();
        for (NodePrepared prepared : eligible.values()) {
            futures.put(prepared.nodeId(), nodeExecutor.submit(() -> {
                try {
                    nodeAdapter.validate(prepared.nodeId(), prepared.candidate(), timeoutMillis);
                    return NodeCheck.ok();
                } catch (NodeRejectedException ex) {
                    return NodeCheck.rejected(ex.getMessage());
                } catch (NodeTimeoutException ex) {
                    return NodeCheck.timeout();
                }
            }));
        }
        Map<String, NodeCheck> results = new LinkedHashMap<>();
        for (Map.Entry<String, Future<NodeCheck>> entry : futures.entrySet()) {
            try {
                results.put(entry.getKey(), entry.getValue().get(timeoutMillis, TimeUnit.MILLISECONDS));
            } catch (TimeoutException ex) {
                entry.getValue().cancel(true);
                results.put(entry.getKey(), NodeCheck.timeout());
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                results.put(entry.getKey(), NodeCheck.timeout());
            } catch (ExecutionException ex) {
                log.warn("node validation failed unexpectedly", ex.getCause());
                results.put(entry.getKey(), NodeCheck.rejected(
                        ex.getCause() == null ? ex.getMessage() : ex.getCause().getMessage()));
            }
        }
        return results;
    }

    private TransactionResponse finishTransaction(TransactionRequest request,
                                                  String requestHash,
                                                  TransactionStatus status,
                                                  Map<String, NodeOutcome> outcomes,
                                                  Long eventCursor) {
        TransactionResponse response = new TransactionResponse(
                request.transactionId(), status, outcomes, eventCursor, false);
        transactionRecordRepository.insert(new TransactionRecord(
                request.transactionId(), requestHash, status,
                writeJson(response), Instant.now(clock)));
        return response;
    }

    private TransactionResponse replayOrConflict(String transactionId,
                                                 String requestHash,
                                                 TransactionRecord record) {
        if (!record.requestHash().equals(requestHash)) {
            throw new ConflictException("transactionId '" + transactionId
                    + "' already exists with different parameters");
        }
        try {
            TransactionResponse original = responseMapper.readValue(
                    record.responseJson(), TransactionResponse.class);
            return new TransactionResponse(original.transactionId(), original.status(),
                    original.nodes(), original.eventCursor(), true);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("stored transaction response is unreadable", ex);
        }
    }

    private String canonicalHash(TransactionRequest request) {
        try {
            String canonical = canonicalMapper.writeValueAsString(request);
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (JsonProcessingException | NoSuchAlgorithmException ex) {
            throw new IllegalStateException("failed to hash transaction request", ex);
        }
    }

    private String writeJson(Object value) {
        try {
            return canonicalMapper.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("failed to serialize json", ex);
        }
    }

    private record NodeCheck(CheckKind kind, String message) {
        static NodeCheck ok() {
            return new NodeCheck(CheckKind.OK, null);
        }

        static NodeCheck rejected(String reason) {
            return new NodeCheck(CheckKind.REJECTED, reason);
        }

        static NodeCheck timeout() {
            return new NodeCheck(CheckKind.TIMEOUT, null);
        }
    }

    private enum CheckKind { OK, REJECTED, TIMEOUT }

    private record ProcessResult(TransactionResponse response, List<NodePrepared> toApply) {
    }
}
