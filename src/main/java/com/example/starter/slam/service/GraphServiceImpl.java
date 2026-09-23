package com.example.starter.slam.service;

import com.example.starter.slam.api.EdgeRequest;
import com.example.starter.slam.api.EdgeView;
import com.example.starter.slam.api.KeyFrameRequest;
import com.example.starter.slam.api.KeyFrameView;
import com.example.starter.slam.domain.EdgeStatus;
import com.example.starter.slam.domain.EdgeType;
import com.example.starter.slam.optim.LoopCheckResult;
import com.example.starter.slam.optim.LoopClosurePolicy;
import com.example.starter.slam.po.EdgePO;
import com.example.starter.slam.po.KeyFramePO;
import com.example.starter.slam.po.PoseGraphPO;
import com.example.starter.slam.store.GraphStore;
import com.example.starter.slam.support.ContentHashes;
import com.example.starter.slam.web.ConflictException;
import com.example.starter.slam.web.NotFoundException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/**
 * 位姿图写入服务实现：保证关键帧/边幂等写入、同 ID 或同 requestId 改参 409、
 * 边引用完整性、回环版本化阈值校验；REJECTED 回环仅审计且不递增图版本。
 */
@Service
public class GraphServiceImpl implements GraphService {

    private final GraphStore graphStore;
    private final LoopClosurePolicy loopClosurePolicy;
    private final ObjectMapper objectMapper;

    public GraphServiceImpl(GraphStore graphStore, LoopClosurePolicy loopClosurePolicy,
                            ObjectMapper objectMapper) {
        this.graphStore = graphStore;
        this.loopClosurePolicy = loopClosurePolicy;
        this.objectMapper = objectMapper;
    }

    @Override
    @Transactional
    public KeyFrameView putFrame(String graphId, String pathFrameId, KeyFrameRequest request) {
        String frameId = requirePathIdMatch(pathFrameId, request.id(), "frame");

        assertFinite(request.pose(), "pose");
        String poseJson = toJson(request.pose());
        String contentHash = ContentHashes.sha256(List.of(
                request.source(), request.version(), request.pose()));

        PoseGraphPO graph = graphStore.getOrCreateGraph(graphId);

        KeyFramePO existing = graphStore.findFrame(frameId);
        if (existing != null) {
            if (!graphId.equals(existing.graphId())) {
                throw new ConflictException(
                        "key frame " + frameId + " already exists in graph " + existing.graphId());
            }
            if (!contentHash.equals(existing.contentHash())) {
                throw new ConflictException(
                        "key frame " + frameId + " already exists with different content");
            }
            return new KeyFrameView(existing.id(), existing.source(), existing.version(),
                    existing.graphId(), true, existing.createdAt());
        }

        Instant now = Instant.now();
        KeyFramePO frame = new KeyFramePO(frameId, graphId, request.source(), request.version(),
                poseJson, contentHash, now);
        try {
            graphStore.insertFrame(frame);
        } catch (DuplicateKeyException ex) {
            // 并发首次写入竞争：退化为冲突/幂等判定
            KeyFramePO raced = graphStore.findFrame(frameId);
            if (raced != null && contentHash.equals(raced.contentHash()) && graphId.equals(raced.graphId())) {
                return new KeyFrameView(raced.id(), raced.source(), raced.version(),
                        raced.graphId(), true, raced.createdAt());
            }
            throw new ConflictException("key frame " + frameId + " already exists with different content");
        }
        long graphVersion = graphStore.incrementVersion(graphId);

        return new KeyFrameView(frameId, request.source(), request.version(), graphId,
                false, now);
    }

    @Override
    @Transactional
    public EdgeView putEdge(String graphId, String pathEdgeId, EdgeRequest request) {
        String edgeId = requirePathIdMatch(pathEdgeId, request.id(), "edge");

        assertFinite(request.measurement(), "measurement");
        if (request.information() != null) {
            assertFinite(request.information(), "information");
        }

        String measurementJson = toJson(request.measurement());
        String informationJson = request.information() == null ? null : toJson(request.information());
        String contentHash = ContentHashes.sha256(List.of(
                request.requestId(), request.source(), request.version(), request.type().name(),
                request.fromFrameId(), request.toFrameId(), request.measurement(),
                request.information() == null ? List.of() : request.information(),
                request.score() == null ? "null" : request.score(),
                request.residual() == null ? "null" : request.residual()));

        PoseGraphPO graph = graphStore.getOrCreateGraph(graphId);

        EdgePO existingById = graphStore.findEdgeById(edgeId);
        EdgePO existingByRequest = graphStore.findEdgeByRequestId(request.requestId());
        if (existingById != null || existingByRequest != null) {
            EdgePO existing = existingById != null ? existingById : existingByRequest;
            if (!contentHash.equals(existing.contentHash())
                    || (existingById != null && !request.requestId().equals(existingById.requestId()))
                    || (existingByRequest != null && !edgeId.equals(existingByRequest.id()))) {
                throw new ConflictException(
                        "edge id " + edgeId + " or requestId " + request.requestId()
                                + " already exists with different content");
            }
            // 完全相同的重复写入：返回原结果
            return toView(existing, graphStore.currentVersion(graphId), true);
        }

        // 引用完整性：两端关键帧必须已存在且属于同一图
        requireFrameInGraph(request.fromFrameId(), graphId);
        requireFrameInGraph(request.toFrameId(), graphId);

        EdgeStatus status;
        String rejectReason = null;
        if (request.type() == EdgeType.LOOP) {
            LoopCheckResult check = loopClosurePolicy.check(
                    request.version(), request.score(), request.residual(), request.information());
            if (!check.isTrusted()) {
                status = EdgeStatus.REJECTED;
                rejectReason = check.getReason();
            } else {
                status = EdgeStatus.VALID;
            }
        } else {
            status = EdgeStatus.VALID;
        }

        Instant now = Instant.now();
        EdgePO edge = new EdgePO(edgeId, request.requestId(), graphId, request.source(), request.version(),
                request.type(), request.fromFrameId(), request.toFrameId(),
                measurementJson, informationJson, request.score(), request.residual(),
                status, rejectReason, contentHash, now);
        try {
            graphStore.insertEdge(edge);
        } catch (DuplicateKeyException ex) {
            EdgePO racedById = graphStore.findEdgeById(edgeId);
            EdgePO racedByRequest = graphStore.findEdgeByRequestId(request.requestId());
            EdgePO raced = racedById != null ? racedById : racedByRequest;
            if (raced != null && contentHash.equals(raced.contentHash())
                    && racedById != null && request.requestId().equals(racedById.requestId())) {
                return toView(raced, graphStore.currentVersion(graphId), true);
            }
            throw new ConflictException("edge id " + edgeId + " or requestId " + request.requestId()
                    + " already exists with different content");
        }

        long graphVersion;
        if (status == EdgeStatus.VALID) {
            graphVersion = graphStore.incrementVersion(graphId);
        } else {
            // 不可信回环仅保存审计记录，不进入有效图，不触发版本递增
            graphVersion = graphStore.currentVersion(graphId);
        }

        return toView(edge, graphVersion, false);
    }

    private void requireFrameInGraph(String frameId, String graphId) {
        KeyFramePO frame = graphStore.findFrame(frameId);
        if (frame == null) {
            throw new NotFoundException("key frame not found: " + frameId);
        }
        if (!graphId.equals(frame.graphId())) {
            throw new ConflictException("key frame " + frameId + " belongs to graph " + frame.graphId()
                    + " rather than " + graphId);
        }
    }

    private EdgeView toView(EdgePO edge, long graphVersion, boolean duplicated) {
        return new EdgeView(edge.id(), edge.requestId(), edge.type(), edge.status(), graphVersion,
                duplicated, edge.rejectReason(), edge.createdAt());
    }

    private String requirePathIdMatch(String pathId, String bodyId, String resource) {
        if (bodyId == null || bodyId.isBlank()) {
            throw new IllegalArgumentException(resource + " id in body is required");
        }
        if (!pathId.equals(bodyId)) {
            throw new IllegalArgumentException(
                    resource + " id in path (" + pathId + ") does not match body (" + bodyId + ")");
        }
        return pathId;
    }

    private void assertFinite(List<Double> values, String field) {
        for (Double value : values) {
            if (value == null || !Double.isFinite(value)) {
                throw new IllegalArgumentException(field + " must contain finite numbers");
            }
        }
    }

    private String toJson(List<Double> values) {
        try {
            return objectMapper.writeValueAsString(values);
        } catch (JsonProcessingException ex) {
            throw new IllegalArgumentException("failed to serialize numeric list", ex);
        }
    }
}
