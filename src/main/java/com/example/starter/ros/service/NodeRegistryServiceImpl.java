package com.example.starter.ros.service;

import com.example.starter.ros.api.EventView;
import com.example.starter.ros.api.NodeView;
import com.example.starter.ros.api.RegisterNodeRequest;
import com.example.starter.ros.api.ResourceSpec;
import com.example.starter.ros.api.ResourceView;
import com.example.starter.ros.api.RestoreResult;
import com.example.starter.ros.api.TransitionRequest;
import com.example.starter.ros.data.NodeRegistryRepository;
import com.example.starter.ros.data.NodeRegistryRepository.IdempotentRow;
import com.example.starter.ros.domain.EventPo;
import com.example.starter.ros.domain.EventTypes;
import com.example.starter.ros.domain.LifecycleState;
import com.example.starter.ros.domain.NodePo;
import com.example.starter.ros.domain.RegistryErrorCode;
import com.example.starter.ros.domain.RegistryException;
import com.example.starter.ros.domain.ResourceCategory;
import com.example.starter.ros.domain.ResourcePo;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

/**
 * 注册中心业务实现。
 *
 * <p>关键语义：
 * <ul>
 *     <li>所有写操作以 requestId 幂等：同参重放返回首请求结果，异参重放 409；</li>
 *     <li>Service/Action 在存活节点间同名互斥（JVM 段锁 + 数据库计数双重保证）；</li>
 *     <li>异常退出在单事务内清理全部资源且只发布一次 NODE_CRASH_CLEANUP 事件；</li>
 *     <li>事件 revision 从事务内单行序列分配，保证全局递增连续；</li>
 *     <li>检查点恢复重建节点/版本/资源/事件/已处理 requestId，不产生新事件。</li>
 * </ul>
 */
@Service
public class NodeRegistryServiceImpl implements NodeRegistryService {

    /** 幂等请求类别。 */
    private static final String KIND_REGISTER = "REGISTER";
    private static final String KIND_DEREGISTER = "DEREGISTER";
    private static final String KIND_TRANSITION = "TRANSITION";
    private static final String KIND_CRASH = "CRASH";
    private static final String KIND_CHECKPOINT_SAVE = "CHECKPOINT_SAVE";
    private static final String KIND_CHECKPOINT_RESTORE = "CHECKPOINT_RESTORE";

    /** 并发同 requestId 请求等待首请求完成的最长时间。 */
    private static final long INFLIGHT_WAIT_SECONDS = 30;

    private final NodeRegistryRepository repository;
    private final TransactionTemplate tx;
    private final ObjectMapper objectMapper;

    /** requestId -> 首请求完成信号，协调同 JVM 并发同参请求。 */
    private final Map<String, CompletableFuture<Void>> inProcess = new ConcurrentHashMap<>();

    /**
     * 注册流程的 JVM 内串行锁（公平）。互斥名为存活节点全局范围，数据库计数无法单独防止
     * 两个并发事务同时读到 0 个持有者，故在 JVM 内串行化“互斥名校验 + 资源插入”；
     * 锁仅在注册业务事务期间持有，幂等重放不进入该临界区。
     */
    private final ReentrantLock registerLock = new ReentrantLock(true);

    public NodeRegistryServiceImpl(NodeRegistryRepository repository,
                                   PlatformTransactionManager transactionManager,
                                   ObjectMapper objectMapper) {
        this.repository = repository;
        this.tx = new TransactionTemplate(transactionManager);
        this.objectMapper = objectMapper;
    }

    // ==================== 注册 ====================

    @Override
    public NodeView register(RegisterNodeRequest request) {
        List<ParsedResource> parsed = parseResources(request.resources());
        String paramsHash = ParamsHasher.sha256(normalizeRegister(request));

        registerLock.lock();
        try {
            return executeIdempotent(request.requestId(), KIND_REGISTER, paramsHash, request.nodeId(),
                    NodeView.class, 201, () -> doRegister(request, parsed));
        } finally {
            registerLock.unlock();
        }
    }

    private NodeView doRegister(RegisterNodeRequest request, List<ParsedResource> parsed) {
        boolean existed = repository.findNode(request.nodeId()).isPresent();
        if (existed) {
            NodePo existing = repository.findNode(request.nodeId()).orElseThrow();
            if (existing.alive()) {
                throw new RegistryException(RegistryErrorCode.NODE_ALREADY_REGISTERED,
                        "node already registered: " + request.nodeId());
            }
            // 崩溃节点以同一 nodeId 重新注册：资源已在崩溃事务中清空，复活为全新节点
            int rows = repository.reactivateCrashedNode(request.nodeId(), request.namespace(), request.nodeName());
            if (rows == 0) {
                throw new RegistryException(RegistryErrorCode.NODE_ALREADY_REGISTERED,
                        "node already registered: " + request.nodeId());
            }
        } else {
            try {
                repository.insertNode(new NodePo(request.nodeId(), request.namespace(), request.nodeName(),
                        LifecycleState.UNCONFIGURED, 1L, true, Instant.now(), Instant.now()));
            } catch (DuplicateKeyException ex) {
                // 并发同 nodeId 注册：后提交者失败
                throw new RegistryException(RegistryErrorCode.NODE_ALREADY_REGISTERED,
                        "node already registered: " + request.nodeId());
            }
        }

        List<EventPo> newEvents = new ArrayList<>();
        for (ParsedResource resource : parsed) {
            if (resource.category().isExclusive()
                    && repository.countExclusiveHolders(resource.category(), resource.fqn()) > 0) {
                throw new RegistryException(RegistryErrorCode.RESOURCE_CONFLICT,
                        "exclusive resource name already held by another live node: "
                                + resource.category() + " " + resource.fqn());
            }
            try {
                repository.insertResource(request.nodeId(), resource.category(), resource.fqn(),
                        resource.interfaceType());
            } catch (DuplicateKeyException ex) {
                throw new RegistryException(RegistryErrorCode.RESOURCE_CONFLICT,
                        "duplicated resource declaration: " + resource.category() + " " + resource.fqn());
            }
            long revision = repository.nextRevision();
            newEvents.add(new EventPo(revision, request.nodeId(), EventTypes.RESOURCE_DECLARED,
                    resource.category(), resource.fqn(), resource.interfaceType(), null, Instant.now()));
        }
        for (EventPo event : newEvents) {
            repository.insertEvent(event);
        }
        return loadNodeView(request.nodeId());
    }

    // ==================== 注销 ====================

    @Override
    public NodeView deregister(String nodeId, String requestId) {
        String paramsHash = ParamsHasher.sha256("deregister|" + nodeId);
        return executeIdempotent(requestId, KIND_DEREGISTER, paramsHash, nodeId, NodeView.class, 200,
                () -> doDeregister(nodeId));
    }

    private NodeView doDeregister(String nodeId) {
        NodePo node = repository.findNodeForUpdate(nodeId)
                .orElseThrow(() -> new RegistryException(RegistryErrorCode.NODE_NOT_FOUND,
                        "node not found: " + nodeId));
        List<ResourcePo> resources = repository.findResourcesByNode(nodeId);
        for (ResourcePo resource : resources) {
            long revision = repository.nextRevision();
            repository.insertEvent(new EventPo(revision, nodeId, EventTypes.RESOURCE_RELEASED,
                    resource.resourceCategory(), resource.fullyQualifiedName(),
                    resource.interfaceType(), "deregister", Instant.now()));
        }
        repository.deleteResourcesByNode(nodeId);
        // 先删资源再删节点，满足外键约束；节点行删除即注销完成
        repository.deleteNode(nodeId);
        return toView(node, resources);
    }

    // ==================== 生命周期迁移 ====================

    @Override
    public NodeView transition(String nodeId, TransitionRequest request) {
        LifecycleState target;
        try {
            target = LifecycleState.valueOf(request.targetState().trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            throw new RegistryException(RegistryErrorCode.VALIDATION_ERROR,
                    "unknown target state: " + request.targetState());
        }
        String paramsHash = ParamsHasher.sha256(
                "transition|" + nodeId + "|" + target + "|" + request.expectedVersion());
        return executeIdempotent(request.requestId(), KIND_TRANSITION, paramsHash, nodeId,
                NodeView.class, 200, () -> doTransition(nodeId, target, request.expectedVersion()));
    }

    private NodeView doTransition(String nodeId, LifecycleState target, long expectedVersion) {
        NodePo node = repository.findNodeForUpdate(nodeId)
                .orElseThrow(() -> new RegistryException(RegistryErrorCode.NODE_NOT_FOUND,
                        "node not found: " + nodeId));
        if (!node.alive()) {
            throw new RegistryException(RegistryErrorCode.NODE_CRASHED,
                    "node has crashed: " + nodeId);
        }
        if (node.lifecycleState() == LifecycleState.FINALIZED) {
            // 已终结节点迁移与其它非法迁移必须可区分：410 NODE_FINALIZED
            throw new RegistryException(RegistryErrorCode.NODE_FINALIZED,
                    "node already finalized: " + nodeId);
        }
        if (node.version() != expectedVersion) {
            // 版本冲突独立错误码：409 VERSION_CONFLICT
            throw new RegistryException(RegistryErrorCode.VERSION_CONFLICT,
                    "version conflict: expected " + expectedVersion + ", actual " + node.version());
        }
        if (!node.lifecycleState().canTransitionTo(target)) {
            // 非法相邻迁移：422 ILLEGAL_TRANSITION
            throw new RegistryException(RegistryErrorCode.ILLEGAL_TRANSITION,
                    "illegal transition: " + node.lifecycleState() + " -> " + target);
        }
        long newVersion = node.version() + 1;
        repository.updateNodeState(nodeId, target, newVersion);
        long revision = repository.nextRevision();
        repository.insertEvent(new EventPo(revision, nodeId, EventTypes.LIFECYCLE_TRANSITION,
                null, null, null,
                node.lifecycleState() + "->" + target, Instant.now()));
        return loadNodeView(nodeId);
    }

    // ==================== 异常退出模拟 ====================

    @Override
    public NodeView crash(String nodeId, String requestId) {
        String paramsHash = ParamsHasher.sha256("crash|" + nodeId);
        return executeIdempotent(requestId, KIND_CRASH, paramsHash, nodeId, NodeView.class, 200,
                () -> doCrash(nodeId));
    }

    private NodeView doCrash(String nodeId) {
        NodePo node = repository.findNodeForUpdate(nodeId)
                .orElseThrow(() -> new RegistryException(RegistryErrorCode.NODE_NOT_FOUND,
                        "node not found: " + nodeId));
        if (!node.alive()) {
            // 重复崩溃（不同 requestId）不再清理、不再发事件
            throw new RegistryException(RegistryErrorCode.NODE_CRASHED,
                    "node already crashed: " + nodeId);
        }
        if (node.lifecycleState() == LifecycleState.FINALIZED) {
            throw new RegistryException(RegistryErrorCode.NODE_FINALIZED,
                    "node already finalized: " + nodeId);
        }
        List<ResourcePo> resources = repository.findResourcesByNode(nodeId);
        // 同一事务：删除全部资源 + 标记死亡 + 仅一条清理事件
        int released = repository.deleteResourcesByNode(nodeId);
        repository.markNodeCrashed(nodeId);
        long revision = repository.nextRevision();
        repository.insertEvent(new EventPo(revision, nodeId, EventTypes.NODE_CRASH_CLEANUP,
                null, null, null,
                "crashed; released " + released + " resource(s)", Instant.now()));
        NodePo crashedView = new NodePo(node.nodeId(), node.namespace(), node.nodeName(),
                node.lifecycleState(), node.version(), false, node.registeredAt(), Instant.now());
        return toView(crashedView, List.of());
    }

    // ==================== 查询 ====================

    @Override
    public NodeView getNode(String nodeId) {
        NodePo node = repository.findNode(nodeId)
                .orElseThrow(() -> new RegistryException(RegistryErrorCode.NODE_NOT_FOUND,
                        "node not found: " + nodeId));
        return toView(node, repository.findResourcesByNode(nodeId));
    }

    @Override
    public List<NodeView> listNodes() {
        List<NodeView> views = new ArrayList<>();
        for (NodePo node : repository.findAllNodes()) {
            views.add(toView(node, repository.findResourcesByNode(node.nodeId())));
        }
        return views;
    }

    @Override
    public List<EventView> eventsAfter(long afterRevision, int limit) {
        if (afterRevision < 0) {
            throw new RegistryException(RegistryErrorCode.VALIDATION_ERROR,
                    "after revision must be >= 0");
        }
        List<EventView> views = new ArrayList<>();
        for (EventPo event : repository.findEventsAfter(afterRevision, limit)) {
            views.add(toEventView(event));
        }
        return views;
    }

    // ==================== 检查点 ====================

    @Override
    public void saveCheckpoint(String checkpointId, String requestId) {
        String paramsHash = ParamsHasher.sha256("checkpoint-save|" + checkpointId);
        executeIdempotent(requestId, KIND_CHECKPOINT_SAVE, paramsHash, null, Void.class, 201,
                () -> {
                    if (repository.checkpointExists(checkpointId)) {
                        throw new RegistryException(RegistryErrorCode.CHECKPOINT_ERROR,
                                "checkpoint already exists: " + checkpointId);
                    }
                    CheckpointSnapshot snapshot = new CheckpointSnapshot(
                            1, Instant.now(), repository.currentRevisionValue(),
                            repository.findAllNodes(),
                            repository.findAllResources(),
                            repository.findAllEvents(),
                            repository.findAllIdempotents().stream()
                                    .filter(row -> row.httpStatus() != null)
                                    .toList());
                    repository.saveCheckpoint(checkpointId, writeJson(snapshot));
                    return null;
                });
    }

    @Override
    public RestoreResult restoreCheckpoint(String checkpointId, String requestId) {
        String paramsHash = ParamsHasher.sha256("checkpoint-restore|" + checkpointId);
        return executeIdempotent(requestId, KIND_CHECKPOINT_RESTORE, paramsHash, null,
                RestoreResult.class, 200, () -> doRestore(checkpointId, requestId));
    }

    private RestoreResult doRestore(String checkpointId, String currentRequestId) {
        String payload = repository.findCheckpoint(checkpointId)
                .orElseThrow(() -> new RegistryException(RegistryErrorCode.CHECKPOINT_ERROR,
                        "checkpoint not found: " + checkpointId));
        CheckpointSnapshot snapshot;
        try {
            snapshot = objectMapper.readValue(payload, CheckpointSnapshot.class);
        } catch (JsonProcessingException ex) {
            throw new RegistryException(RegistryErrorCode.CHECKPOINT_ERROR,
                    "corrupt checkpoint payload: " + checkpointId);
        }

        // 单事务重建全部业务表；恢复不发布任何新事件，revision 游标回到检查点时刻；
        // 保留当前恢复请求自身的占坑行，使其结果仍可落盘与重放
        repository.clearBusinessTables(currentRequestId);
        for (NodePo node : snapshot.nodes()) {
            repository.insertNode(node);
        }
        for (ResourcePo resource : snapshot.resources()) {
            repository.insertRestoredResource(resource);
        }
        for (EventPo event : snapshot.events()) {
            repository.insertEvent(event);
        }
        for (IdempotentRow row : snapshot.idempotents()) {
            repository.insertCompletedIdempotent(row);
        }
        repository.resetRevisionValue(snapshot.revisionNext());

        List<NodeView> nodeViews = snapshot.nodes().stream()
                .map(node -> toView(node, resourcesOf(snapshot, node.nodeId())))
                .toList();
        List<String> requestIds = snapshot.idempotents().stream()
                .map(IdempotentRow::requestId)
                .sorted()
                .toList();
        long lastRevision = snapshot.events().stream()
                .mapToLong(EventPo::revision)
                .max()
                .orElse(0L);
        return new RestoreResult(checkpointId, nodeViews, requestIds, lastRevision);
    }

    private static List<ResourcePo> resourcesOf(CheckpointSnapshot snapshot, String nodeId) {
        return snapshot.resources().stream()
                .filter(r -> r.nodeId().equals(nodeId))
                .toList();
    }

    /**
     * 检查点快照载荷（当前 JVM 内恢复使用）。
     *
     * @param version      快照格式版本
     * @param savedAt      保存时间
     * @param revisionNext 保存时刻 revision 游标值
     * @param nodes        全部节点（含版本/存活/状态）
     * @param resources    全部资源
     * @param events       全部已确认事件
     * @param idempotents  已完成的幂等请求记录
     */
    private record CheckpointSnapshot(
            int version,
            Instant savedAt,
            long revisionNext,
            List<NodePo> nodes,
            List<ResourcePo> resources,
            List<EventPo> events,
            List<IdempotentRow> idempotents) {
    }

    // ==================== 幂等执行模板 ====================

    /**
     * 以 requestId 为键执行幂等操作。
     *
     * <p>占坑与业务执行分属两个事务：占坑事务提交后，并发同参请求即可观察到 INFLIGHT 并等待；
     * 业务事务完成后写入首请求结果。成功返回存储结果，业务失败存储错误结果并重抛。
     */
    private <T> T executeIdempotent(String requestId, String kind, String paramsHash, String nodeId,
                                    Class<T> bodyType, int successStatus, Supplier<T> action) {
        while (true) {
            Claim claim = claimInTransaction(requestId, kind, paramsHash, nodeId);
            switch (claim.kind()) {
                case CLAIMED -> {
                    CompletableFuture<Void> done = new CompletableFuture<>();
                    inProcess.put(requestId, done);
                    try {
                        T result = tx.execute(status -> action.get());
                        String body = result == null ? null : writeJson(result);
                        tx.executeWithoutResult(s -> repository.completeIdempotent(
                                requestId, successStatus, body));
                        done.complete(null);
                        return result;
                    } catch (RegistryException ex) {
                        completeWithError(requestId, ex);
                        done.complete(null);
                        throw ex;
                    } catch (RuntimeException ex) {
                        // 非预期异常释放占坑，允许客户端用同 requestId 重试
                        safeReleaseInflight(requestId);
                        done.completeExceptionally(ex);
                        throw ex;
                    } finally {
                        inProcess.remove(requestId, done);
                    }
                }
                case COMPLETED -> {
                    IdempotentRow row = claim.row();
                    return replay(row, bodyType);
                }
                case PARAM_MISMATCH -> throw new RegistryException(
                        RegistryErrorCode.IDEMPOTENCY_PARAM_MISMATCH,
                        "requestId already used with different parameters: " + requestId);
                case INFLIGHT -> {
                    if (!awaitInflight(requestId)) {
                        throw new RegistryException(RegistryErrorCode.IDEMPOTENCY_INFLIGHT_TIMEOUT,
                                "concurrent request with same requestId still in progress: " + requestId);
                    }
                    // 首请求已完成，回到循环重新读取存储结果
                }
            }
        }
    }

    /**
     * 占坑事务：返回占坑结果。
     */
    private Claim claimInTransaction(String requestId, String kind, String paramsHash, String nodeId) {
        return tx.execute(status -> {
            IdempotentRow existing = repository.findIdempotent(requestId).orElse(null);
            if (existing != null) {
                if (!existing.paramsHash().equals(paramsHash) || !kind.equals(existing.requestKind())) {
                    return Claim.paramMismatch();
                }
                if (existing.httpStatus() != null) {
                    return Claim.completed(existing);
                }
                return Claim.inflight();
            }
            if (repository.tryInsertInflight(requestId, kind, paramsHash, nodeId)) {
                return Claim.claimed();
            }
            // 并发占坑抢先，按 INFLIGHT 处理并在外层等待
            return Claim.inflight();
        });
    }

    @SuppressWarnings("unchecked")
    private <T> T replay(IdempotentRow row, Class<T> bodyType) {
        int status = row.httpStatus();
        if (status >= 200 && status < 300) {
            if (bodyType == Void.class || row.responseBody() == null) {
                return null;
            }
            try {
                return objectMapper.readValue(row.responseBody(), bodyType);
            } catch (JsonProcessingException ex) {
                throw new IllegalStateException("stored response unreadable", ex);
            }
        }
        // 重放首请求的失败结果：从错误体恢复错误码与消息，保持语义可区分
        RegistryErrorCode errorCode = RegistryErrorCode.ILLEGAL_TRANSITION;
        String message = "replayed failed request";
        try {
            JsonNode errorBody = objectMapper.readTree(row.responseBody());
            errorCode = RegistryErrorCode.valueOf(errorBody.path("errorCode").asText(errorCode.name()));
            message = errorBody.path("message").asText(message);
        } catch (JsonProcessingException ignored) {
            // 存储体不可读时使用默认错误码
        }
        throw new RegistryException(errorCode, message);
    }

    private void completeWithError(String requestId, RegistryException ex) {
        try {
            String errorBody = writeJson(Map.of(
                    "errorCode", ex.getErrorCode().name(),
                    "message", String.valueOf(ex.getMessage())));
            tx.executeWithoutResult(s ->
                    repository.completeIdempotent(requestId, ex.getErrorCode().httpStatus(), errorBody));
        } catch (RuntimeException persistFailure) {
            // 错误结果无法落盘时回滚占坑，避免请求被永久挂起
            safeReleaseInflight(requestId);
        }
    }

    private void safeReleaseInflight(String requestId) {
        try {
            tx.executeWithoutResult(s -> repository.deleteIdempotent(requestId));
        } catch (RuntimeException ignored) {
            // 释放失败只能等待后续人工介入，记录不重抛以免掩盖原异常
        }
    }

    private boolean awaitInflight(String requestId) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(INFLIGHT_WAIT_SECONDS);
        try {
            while (System.nanoTime() < deadline) {
                CompletableFuture<Void> done = inProcess.get(requestId);
                if (done != null) {
                    long remaining = deadline - System.nanoTime();
                    done.get(Math.max(remaining, 1L), TimeUnit.NANOSECONDS);
                    return true;
                }
                // 占坑已可见但完成信号尚未登记，短暂退让后重读
                Thread.sleep(10L);
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return false;
        } catch (ExecutionException ex) {
            // 首请求异常结束并已释放占坑：回到外层循环重新走占坑/读取
            return true;
        } catch (TimeoutException ex) {
            return false;
        }
        return false;
    }

    /** 占坑结论。 */
    private record Claim(ClaimKind kind, IdempotentRow row) {
        static Claim claimed() {
            return new Claim(ClaimKind.CLAIMED, null);
        }

        static Claim completed(IdempotentRow row) {
            return new Claim(ClaimKind.COMPLETED, row);
        }

        static Claim paramMismatch() {
            return new Claim(ClaimKind.PARAM_MISMATCH, null);
        }

        static Claim inflight() {
            return new Claim(ClaimKind.INFLIGHT, null);
        }
    }

    private enum ClaimKind {
        CLAIMED, COMPLETED, PARAM_MISMATCH, INFLIGHT
    }

    // ==================== 解析/归一化/视图转换 ====================

    private record ParsedResource(ResourceCategory category, String fqn, String interfaceType) {
    }

    private List<ParsedResource> parseResources(List<ResourceSpec> specs) {
        List<ParsedResource> result = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (ResourceSpec spec : specs) {
            ResourceCategory category;
            try {
                category = ResourceCategory.from(spec.category());
            } catch (IllegalArgumentException ex) {
                throw new RegistryException(RegistryErrorCode.VALIDATION_ERROR,
                        "unknown resource category: " + spec.category());
            }
            String key = category.name() + "|" + spec.fullyQualifiedName() + "|" + spec.interfaceType();
            if (!seen.add(key)) {
                throw new RegistryException(RegistryErrorCode.VALIDATION_ERROR,
                        "duplicated resource in request: " + spec.fullyQualifiedName());
            }
            result.add(new ParsedResource(category, spec.fullyQualifiedName(), spec.interfaceType()));
        }
        return result;
    }

    private String normalizeRegister(RegisterNodeRequest request) {
        List<String> keys = request.resources().stream()
                .map(r -> r.category().trim().toUpperCase() + "|"
                        + r.fullyQualifiedName() + "|" + r.interfaceType())
                .sorted(Comparator.naturalOrder())
                .toList();
        return "register|" + request.nodeId() + "|" + request.namespace() + "|" + request.nodeName()
                + "|" + String.join(",", keys);
    }

    private NodeView loadNodeView(String nodeId) {
        NodePo node = repository.findNode(nodeId)
                .orElseThrow(() -> new RegistryException(RegistryErrorCode.NODE_NOT_FOUND,
                        "node not found: " + nodeId));
        return toView(node, repository.findResourcesByNode(nodeId));
    }

    private static NodeView toView(NodePo node, List<ResourcePo> resources) {
        List<ResourceView> views = resources.stream()
                .map(r -> new ResourceView(r.resourceCategory().name(),
                        r.fullyQualifiedName(), r.interfaceType()))
                .toList();
        return new NodeView(node.nodeId(), node.namespace(), node.nodeName(),
                node.lifecycleState().name(), node.version(), node.alive(), views);
    }

    private static EventView toEventView(EventPo event) {
        return new EventView(event.revision(), event.nodeId(), event.eventType(),
                event.resourceCategory() == null ? null : event.resourceCategory().name(),
                event.fullyQualifiedName(), event.interfaceType(), event.detail());
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("failed to serialize response", ex);
        }
    }
}
