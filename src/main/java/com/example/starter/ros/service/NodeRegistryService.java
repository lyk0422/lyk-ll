package com.example.starter.ros.service;

import com.example.starter.ros.domain.LifecycleState;
import com.example.starter.ros.domain.NodeCheckpoint;
import com.example.starter.ros.domain.NodeResource;
import com.example.starter.ros.domain.RequestRecord;
import com.example.starter.ros.domain.ResourceCategory;
import com.example.starter.ros.domain.RosNode;
import com.example.starter.ros.repository.CheckpointJdbcRepository;
import com.example.starter.ros.repository.EventJdbcRepository;
import com.example.starter.ros.repository.NodeJdbcRepository;
import com.example.starter.ros.repository.RequestRecordJdbcRepository;
import com.example.starter.ros.repository.ResourceJdbcRepository;
import com.example.starter.ros.service.EventPayloads.ResourceItem;
import com.example.starter.ros.support.RosNames;
import com.example.starter.ros.support.TimeProvider;
import com.example.starter.ros.web.dto.CheckpointResponse;
import com.example.starter.ros.web.dto.NodeOperationRequest;
import com.example.starter.ros.web.dto.NodeResponse;
import com.example.starter.ros.web.dto.RecoverRequest;
import com.example.starter.ros.web.dto.RegisterNodeRequest;
import com.example.starter.ros.web.dto.ResourceSpec;
import com.example.starter.ros.web.dto.ResourceView;
import com.example.starter.ros.web.dto.TransitionRequest;
import com.example.starter.ros.web.error.ApiException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * ROS 2 风格节点注册中心核心服务:注册/注销、互斥资源、幂等请求、
 * 生命周期迁移、异常退出清理、检查点恢复。
 * 所有写操作在单事务内完成资源变更与事件发布,保证失败整体回滚。
 */
@Service
public class NodeRegistryService {

    private static final Logger log = LoggerFactory.getLogger(NodeRegistryService.class);

    public static final String OP_REGISTER = "REGISTER";
    public static final String OP_DEREGISTER = "DEREGISTER";
    public static final String OP_TRANSITION = "TRANSITION";
    public static final String OP_CRASH = "CRASH";
    public static final String OP_RECOVER = "RECOVER";

    private final NodeJdbcRepository nodeRepository;
    private final ResourceJdbcRepository resourceRepository;
    private final RequestRecordJdbcRepository requestRepository;
    private final EventJdbcRepository eventRepository;
    private final CheckpointJdbcRepository checkpointRepository;
    private final TimeProvider timeProvider;
    private final ObjectMapper objectMapper;

    public NodeRegistryService(NodeJdbcRepository nodeRepository,
                               ResourceJdbcRepository resourceRepository,
                               RequestRecordJdbcRepository requestRepository,
                               EventJdbcRepository eventRepository,
                               CheckpointJdbcRepository checkpointRepository,
                               TimeProvider timeProvider,
                               ObjectMapper objectMapper) {
        this.nodeRepository = nodeRepository;
        this.resourceRepository = resourceRepository;
        this.requestRepository = requestRepository;
        this.eventRepository = eventRepository;
        this.checkpointRepository = checkpointRepository;
        this.timeProvider = timeProvider;
        this.objectMapper = objectMapper;
    }

    /**
     * 注册节点并声明资源。同 requestId 同参重放返回原结果,异参返回 409;
     * 重复注册同名存活节点返回 409;互斥资源名被占用返回 409。
     */
    @Transactional
    public NodeResponse register(RegisterNodeRequest request) {
        LocalDateTime now = timeProvider.now();
        List<ParsedResource> parsed = parseResources(request.safeResources(), request.namespace());
        String requestHash = hash(RequestHasher.registerParams(
                request.nodeId(), request.namespace(), request.nodeName(),
                request.safeResources()));

        Optional<ReplayOutcome> replay = reserveRequestId(
                request.requestId(), request.nodeId(), OP_REGISTER, requestHash, now);
        if (replay.isPresent()) {
            return replay.get().response();
        }

        RosNode existing = nodeRepository.findByIdForUpdate(request.nodeId()).orElse(null);
        if (existing != null && !existing.crashed()) {
            throw conflict("NODE_ALREADY_REGISTERED",
                    "节点已注册且仍存活: " + request.nodeId());
        }

        String namespace = RosNames.normalizeNamespace(request.namespace());
        RosNode node;
        if (existing == null) {
            node = new RosNode(request.nodeId(), namespace, request.nodeName(),
                    LifecycleState.UNCONFIGURED, 0L, false, now, now);
            try {
                nodeRepository.insertNode(node);
            } catch (DuplicateKeyException e) {
                throw conflict("NODE_ALREADY_REGISTERED",
                        "节点已注册且仍存活: " + request.nodeId());
            }
        } else {
            // 异常退出墓碑节点重新上线:行重置为全新节点,失败回滚后墓碑仍保留
            node = new RosNode(request.nodeId(), namespace, request.nodeName(),
                    LifecycleState.UNCONFIGURED, 0L, false, existing.createdAt(), now);
            nodeRepository.restoreNode(node);
            checkpointRepository.delete(request.nodeId());
        }

        // 互斥占位先按类别+全限定名全局排序后获取,避免并发请求交叉加锁导致死锁
        List<ParsedResource> exclusives = parsed.stream()
                .filter(p -> p.category().isExclusive())
                .sorted(Comparator.comparing(p -> p.category().name() + "|" + p.fqName()))
                .toList();
        Set<String> exclusiveNamesInRequest = new HashSet<>();
        for (ParsedResource parsedResource : exclusives) {
            if (!exclusiveNamesInRequest.add(
                    parsedResource.category() + "|" + parsedResource.fqName())) {
                throw conflict("RESOURCE_CONFLICT",
                        "同一节点不能重复声明互斥资源名: " + parsedResource.fqName());
            }
            try {
                resourceRepository.tryClaim(parsedResource.category(),
                        parsedResource.fqName(), request.nodeId());
            } catch (DuplicateKeyException e) {
                String holder = resourceRepository
                        .findClaimHolder(parsedResource.category(), parsedResource.fqName())
                        .orElse("其他存活节点");
                throw conflict("RESOURCE_CONFLICT", "互斥资源名已被节点 "
                        + holder + " 占用: " + parsedResource.fqName());
            }
        }

        List<ResourceItem> resourceItems = new ArrayList<>();
        for (ParsedResource parsedResource : parsed) {
            resourceRepository.insertResource(new NodeResource(null, request.nodeId(),
                    parsedResource.category(), parsedResource.fqName(),
                    parsedResource.interfaceType(), now));
            resourceItems.add(new ResourceItem(parsedResource.category().name(),
                    parsedResource.fqName(), parsedResource.interfaceType()));
        }

        EventPayloads.RegisteredPayload payload = new EventPayloads.RegisteredPayload(
                request.nodeId(), namespace, request.nodeName(),
                LifecycleState.UNCONFIGURED.name(), 0L, resourceItems);
        eventRepository.insert(request.nodeId(),
                com.example.starter.ros.domain.EventType.REGISTERED, writeJson(payload), now);

        NodeResponse response = toResponse(node, toViews(resourceItems));
        saveCheckpointSnapshot(node, resourceItems, now);
        requestRepository.updateResponse(request.requestId(), writeJson(response));
        return response;
    }

    /**
     * 主动注销:同事务清理全部资源、释放互斥占位、删除节点并只发布一次注销事件。
     */
    @Transactional
    public NodeResponse deregister(NodeOperationRequest request) {
        LocalDateTime now = timeProvider.now();
        String requestHash = hash(RequestHasher.nodeParams(request.nodeId()));

        Optional<ReplayOutcome> replay = reserveRequestId(
                request.requestId(), request.nodeId(), OP_DEREGISTER, requestHash, now);
        if (replay.isPresent()) {
            return replay.get().response();
        }

        RosNode node = requireLiveNodeForUpdate(request.nodeId());
        if (node.crashed()) {
            throw conflict("NODE_CRASHED", "节点已异常退出,不能主动注销: " + request.nodeId());
        }

        resourceRepository.releaseClaimsByNode(request.nodeId());
        resourceRepository.deleteByNodeId(request.nodeId());
        checkpointRepository.delete(request.nodeId());
        nodeRepository.deleteNode(request.nodeId());
        eventRepository.insert(request.nodeId(),
                com.example.starter.ros.domain.EventType.DEREGISTERED,
                writeJson(new EventPayloads.DeregisteredPayload(request.nodeId())), now);

        NodeResponse response = new NodeResponse(request.nodeId(), node.namespace(),
                node.nodeName(), LifecycleState.FINALIZED.name(), node.version(), false,
                List.of(), node.createdAt(), now);
        requestRepository.updateResponse(request.requestId(), writeJson(response));
        return response;
    }

    /**
     * 生命周期相邻状态迁移。已终结迁移、版本冲突、非法迁移分别返回可区分错误码。
     */
    @Transactional
    public NodeResponse transition(TransitionRequest request) {
        LocalDateTime now = timeProvider.now();
        LifecycleState target = LifecycleState.valueOfSafe(request.targetState());
        if (target == null) {
            throw badRequest("INVALID_TARGET_STATE", "未知目标状态: " + request.targetState());
        }
        String requestHash = hash(RequestHasher.transitionParams(
                request.nodeId(), request.targetState(), request.expectedVersion()));

        Optional<ReplayOutcome> replay = reserveRequestId(
                request.requestId(), request.nodeId(), OP_TRANSITION, requestHash, now);
        if (replay.isPresent()) {
            return replay.get().response();
        }

        RosNode node = requireNode(request.nodeId());
        if (node.state().isFinal()) {
            throw conflict("NODE_FINALIZED", "节点已终结,不能再迁移状态: " + request.nodeId());
        }
        if (node.crashed()) {
            throw conflict("NODE_CRASHED", "节点已异常退出,须先恢复: " + request.nodeId());
        }
        if (node.version() != request.expectedVersion()) {
            throw conflict("VERSION_CONFLICT", "版本冲突:当前版本 " + node.version()
                    + ",请求期望版本 " + request.expectedVersion());
        }
        if (!node.state().canTransitionTo(target)) {
            throw conflict("ILLEGAL_TRANSITION", "非法生命周期迁移: "
                    + node.state() + " -> " + target);
        }

        int updated = nodeRepository.compareAndUpdateState(
                request.nodeId(), request.expectedVersion(), target, now);
        if (updated == 0) {
            RosNode latest = nodeRepository.findById(request.nodeId()).orElse(null);
            if (latest != null && latest.crashed()) {
                throw conflict("NODE_CRASHED", "节点已异常退出,须先恢复: " + request.nodeId());
            }
            throw conflict("VERSION_CONFLICT",
                    "版本冲突:节点版本已被其他请求推进: " + request.nodeId());
        }

        eventRepository.insert(request.nodeId(),
                com.example.starter.ros.domain.EventType.STATE_CHANGED,
                writeJson(new EventPayloads.StateChangedPayload(request.nodeId(),
                        node.state().name(), target.name(), node.version() + 1)), now);

        RosNode changed = new RosNode(node.nodeId(), node.namespace(), node.nodeName(),
                target, node.version() + 1, false, node.createdAt(), now);
        List<ResourceView> resources = resourceRepository.findByNodeId(request.nodeId())
                .stream().map(this::toView).toList();
        NodeResponse response = toResponse(changed, resources);
        saveCheckpointSnapshot(changed, toItems(resources), now);
        requestRepository.updateResponse(request.requestId(), writeJson(response));
        return response;
    }

    /**
     * 模拟节点异常退出:同一事务内清理全部资源、释放互斥占位、只发布一次清理事件。
     * 节点行保留为墓碑(crashed=true),版本保持不变。
     */
    @Transactional
    public NodeResponse crash(NodeOperationRequest request) {
        LocalDateTime now = timeProvider.now();
        String requestHash = hash(RequestHasher.nodeParams(request.nodeId()));

        Optional<ReplayOutcome> replay = reserveRequestId(
                request.requestId(), request.nodeId(), OP_CRASH, requestHash, now);
        if (replay.isPresent()) {
            return replay.get().response();
        }

        RosNode node = requireLiveNodeForUpdate(request.nodeId());
        if (node.crashed()) {
            throw conflict("NODE_ALREADY_CRASHED",
                    "节点已处于异常退出状态: " + request.nodeId());
        }

        List<NodeResource> resources = resourceRepository.findByNodeId(request.nodeId());
        int marked = nodeRepository.markCrashed(request.nodeId(), now);
        if (marked == 0) {
            throw conflict("NODE_ALREADY_CRASHED",
                    "节点已被并发请求标记为异常退出: " + request.nodeId());
        }
        resourceRepository.releaseClaimsByNode(request.nodeId());
        resourceRepository.deleteByNodeId(request.nodeId());
        eventRepository.insert(request.nodeId(),
                com.example.starter.ros.domain.EventType.CRASHED,
                writeJson(new EventPayloads.CrashedPayload(request.nodeId(),
                        resources.stream().map(r -> new ResourceItem(
                                r.category().name(), r.fqName(), r.interfaceType())).toList())),
                now);

        RosNode crashedNode = new RosNode(node.nodeId(), node.namespace(), node.nodeName(),
                node.state(), node.version(), true, node.createdAt(), now);
        NodeResponse response = toResponse(crashedNode, List.of());
        requestRepository.updateResponse(request.requestId(), writeJson(response));
        return response;
    }

    /**
     * 按已保存检查点在当前 JVM 内恢复节点状态、版本与资源;不重放已确认事件。
     */
    @Transactional
    public NodeResponse recover(RecoverRequest request) {
        LocalDateTime now = timeProvider.now();
        String requestHash = hash(RequestHasher.nodeParams(request.nodeId()));

        Optional<ReplayOutcome> replay = reserveRequestId(
                request.requestId(), request.nodeId(), OP_RECOVER, requestHash, now);
        if (replay.isPresent()) {
            return replay.get().response();
        }

        RosNode node = requireNodeForUpdate(request.nodeId());
        if (!node.crashed()) {
            throw conflict("NODE_NOT_CRASHED",
                    "节点未异常退出,无需恢复: " + request.nodeId());
        }
        NodeCheckpoint checkpoint = checkpointRepository.findById(request.nodeId())
                .orElseThrow(() -> conflict("CHECKPOINT_NOT_FOUND",
                        "没有可用于恢复的检查点: " + request.nodeId()));

        List<ResourceItem> resources = readResourceItems(checkpoint.resourcesJson());
        RosNode restored = new RosNode(node.nodeId(), node.namespace(), node.nodeName(),
                checkpoint.state(), checkpoint.version(), false, node.createdAt(), now);
        nodeRepository.restoreNode(restored);

        for (ResourceItem item : resources) {
            ResourceCategory category = ResourceCategory.fromString(item.category());
            if (category != null && category.isExclusive()) {
                try {
                    resourceRepository.claim(category, item.fqName(), request.nodeId());
                } catch (DuplicateKeyException e) {
                    String holder = resourceRepository.findClaimHolder(category, item.fqName())
                            .orElse("其他存活节点");
                    throw conflict("RESOURCE_CONFLICT", "恢复时互斥资源名已被节点 "
                            + holder + " 占用: " + item.fqName());
                }
            }
            resourceRepository.insertResource(new NodeResource(null, request.nodeId(),
                    category == null ? ResourceCategory.TOPIC : category,
                    item.fqName(), item.interfaceType(), now));
        }

        // 恢复本身不发布 REGISTERED/STATE_CHANGED 事件,避免重放已确认历史
        saveCheckpointSnapshot(restored, resources, now);
        NodeResponse response = toResponse(restored, toViews(resources));
        requestRepository.updateResponse(request.requestId(), writeJson(response));
        return response;
    }

    @Transactional(readOnly = true)
    public NodeResponse getNode(String nodeId) {
        RosNode node = requireNode(nodeId);
        List<ResourceView> resources = resourceRepository.findByNodeId(nodeId)
                .stream().map(this::toView).toList();
        return toResponse(node, resources);
    }

    @Transactional(readOnly = true)
    public List<NodeResponse> listNodes() {
        List<NodeResponse> result = new ArrayList<>();
        for (RosNode node : nodeRepository.findAll()) {
            result.add(toResponse(node, resourceRepository.findByNodeId(node.nodeId())
                    .stream().map(this::toView).toList()));
        }
        return result;
    }

    /**
     * 显式保存节点检查点(注册与迁移成功时也会自动保存)。
     */
    @Transactional
    public CheckpointResponse saveCheckpoint(String nodeId) {
        RosNode node = requireNode(nodeId);
        List<ResourceView> resources = resourceRepository.findByNodeId(nodeId)
                .stream().map(this::toView).toList();
        LocalDateTime now = timeProvider.now();
        NodeCheckpoint checkpoint = saveCheckpointSnapshot(node, toItems(resources), now);
        return new CheckpointResponse(nodeId, checkpoint.state().name(), checkpoint.version(),
                resources.size(), checkpoint.processedRequestIds().size(), now);
    }

    // ------------------------------------------------------------------
    // 内部辅助
    // ------------------------------------------------------------------

    private RosNode requireNode(String nodeId) {
        return nodeRepository.findById(nodeId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "NODE_NOT_FOUND",
                        "节点不存在: " + nodeId));
    }

    /**
     * 加节点行锁读取(含异常退出墓碑),用于写事务内序列化对同一节点的竞争。
     */
    private RosNode requireNodeForUpdate(String nodeId) {
        return nodeRepository.findByIdForUpdate(nodeId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "NODE_NOT_FOUND",
                        "节点不存在: " + nodeId));
    }

    /** 写路径使用的行锁读取(语义同 {@link #requireNodeForUpdate})。 */
    private RosNode requireLiveNodeForUpdate(String nodeId) {
        return requireNodeForUpdate(nodeId);
    }

    /**
     * 解析并规范化资源声明,校验类别与名称。
     */
    private List<ParsedResource> parseResources(List<ResourceSpec> specs, String namespace) {
        List<ParsedResource> parsed = new ArrayList<>();
        Set<String> seenKeys = new HashSet<>();
        for (ResourceSpec spec : specs) {
            ResourceCategory category = ResourceCategory.fromString(spec.category());
            if (category == null) {
                throw badRequest("INVALID_RESOURCE_CATEGORY",
                        "未知资源类别: " + spec.category());
            }
            String fqName = RosNames.fullyQualifiedName(namespace, spec.name());
            String key = category + "|" + fqName + "|" + spec.interfaceType();
            if (!seenKeys.add(key)) {
                // 完全相同的资源键视为同一声明,忽略重复项
                continue;
            }
            parsed.add(new ParsedResource(category, fqName, spec.interfaceType()));
        }
        return parsed;
    }

    /**
     * 占用幂等请求ID。若该ID已存在:同操作同参返回重放结果;
     * 异参或跨操作返回 409 IDEMPOTENCY_CONFLICT。
     * 并发占用同一 requestId 时,失败方回退为重放读取。
     */
    private Optional<ReplayOutcome> reserveRequestId(String requestId, String nodeId,
                                                     String op, String requestHash,
                                                     LocalDateTime now) {
        RequestRecord existing = requestRepository.findById(requestId).orElse(null);
        if (existing != null) {
            return Optional.of(replayOrConflict(existing, op, requestHash));
        }
        try {
            requestRepository.insert(new RequestRecord(requestId, nodeId, op,
                    requestHash, "", now));
            return Optional.empty();
        } catch (DuplicateKeyException e) {
            // 并发占用同一 requestId:等待占先事务提交后按重放处理(READ_COMMITTED
            // 下未提交快照尚不可见),最多等待 5 秒。
            RequestRecord concurrent = awaitRecordVisible(requestId);
            return Optional.of(replayOrConflict(concurrent, op, requestHash));
        }
    }

    private RequestRecord awaitRecordVisible(String requestId) {
        long deadline = System.currentTimeMillis() + 5_000L;
        while (System.currentTimeMillis() < deadline) {
            RequestRecord record = requestRepository.findById(requestId).orElse(null);
            if (record != null && record.responseJson() != null
                    && !record.responseJson().isBlank()) {
                return record;
            }
            try {
                Thread.sleep(10L);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw new ApiException(HttpStatus.CONFLICT, "REQUEST_IN_PROGRESS",
                        "相同 requestId 的首次请求仍在处理中,请稍后重放");
            }
        }
        throw new ApiException(HttpStatus.CONFLICT, "REQUEST_IN_PROGRESS",
                "相同 requestId 的首次请求仍在处理中,请稍后重放");
    }

    private ReplayOutcome replayOrConflict(RequestRecord record, String op,
                                           String requestHash) {
        if (!record.op().equals(op) || !record.requestHash().equals(requestHash)) {
            throw new ApiException(HttpStatus.CONFLICT, "IDEMPOTENCY_CONFLICT",
                    "requestId=" + record.requestId() + " 已用于不同参数或不同操作的请求");
        }
        if (record.responseJson() == null || record.responseJson().isBlank()) {
            // 并发重放撞上首请求尚未提交响应:提示调用方稍后重试原请求
            throw new ApiException(HttpStatus.CONFLICT, "REQUEST_IN_PROGRESS",
                    "相同 requestId 的首次请求仍在处理中,请稍后重放");
        }
        try {
            return new ReplayOutcome(objectMapper.readValue(
                    record.responseJson(), NodeResponse.class));
        } catch (Exception e) {
            throw new IllegalStateException("解析幂等响应快照失败", e);
        }
    }

    private NodeCheckpoint saveCheckpointSnapshot(RosNode node, List<ResourceItem> resources,
                                                  LocalDateTime now) {
        List<String> processedIds = requestRepository.findIdsByNodeId(node.nodeId());
        NodeCheckpoint checkpoint = new NodeCheckpoint(node.nodeId(), node.state(),
                node.version(), writeJson(resources), processedIds, now);
        checkpointRepository.upsert(checkpoint);
        return checkpoint;
    }

    private List<ResourceItem> readResourceItems(String json) {
        try {
            return objectMapper.readValue(json, new TypeReference<List<ResourceItem>>() {
            });
        } catch (Exception e) {
            throw new IllegalStateException("解析检查点资源快照失败", e);
        }
    }

    private NodeResponse toResponse(RosNode node, List<ResourceView> resources) {
        return new NodeResponse(node.nodeId(), node.namespace(), node.nodeName(),
                node.state().name(), node.version(), node.crashed(), List.copyOf(resources),
                node.createdAt(), node.updatedAt());
    }

    private ResourceView toView(NodeResource resource) {
        return new ResourceView(resource.category().name(), resource.fqName(),
                resource.interfaceType());
    }

    private List<ResourceView> toViews(List<ResourceItem> items) {
        List<ResourceView> views = new ArrayList<>();
        for (ResourceItem item : items) {
            views.add(new ResourceView(item.category(), item.fqName(), item.interfaceType()));
        }
        return views;
    }

    private List<ResourceItem> toItems(List<ResourceView> views) {
        List<ResourceItem> items = new ArrayList<>();
        for (ResourceView view : views) {
            items.add(new ResourceItem(view.category(), view.fqName(), view.interfaceType()));
        }
        return items;
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("序列化JSON失败", e);
        }
    }

    private String hash(Map<String, Object> params) {
        return RequestHasher.sha256(writeJson(params));
    }

    private ApiException conflict(String code, String message) {
        return new ApiException(HttpStatus.CONFLICT, code, message);
    }

    private ApiException badRequest(String code, String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, code, message);
    }

    private record ParsedResource(ResourceCategory category, String fqName,
                                  String interfaceType) {
    }

    private record ReplayOutcome(NodeResponse response) {
    }
}
