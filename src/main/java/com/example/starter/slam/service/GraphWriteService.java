package com.example.starter.slam.service;

import com.example.starter.slam.config.SlamProperties;
import com.example.starter.slam.domain.GraphEdge;
import com.example.starter.slam.domain.Keyframe;
import com.example.starter.slam.model.EdgeStatus;
import com.example.starter.slam.model.EdgeType;
import com.example.starter.slam.repo.EdgeRepository;
import com.example.starter.slam.repo.GraphMetaRepository;
import com.example.starter.slam.repo.KeyframeRepository;
import com.example.starter.slam.repo.OptimizationTaskRepository;
import com.example.starter.slam.web.ConflictException;
import com.example.starter.slam.web.UnprocessableException;
import com.example.starter.slam.web.dto.EdgeRequest;
import com.example.starter.slam.web.dto.KeyframeRequest;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

/**
 * 图写入服务：关键帧/边判重、冲突检测、回环可信度校验、图版本递增。
 */
@Service
public class GraphWriteService {

    private final KeyframeRepository keyframeRepository;
    private final EdgeRepository edgeRepository;
    private final GraphMetaRepository graphMetaRepository;
    private final OptimizationTaskRepository taskRepository;
    private final SlamProperties properties;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public GraphWriteService(KeyframeRepository keyframeRepository,
                             EdgeRepository edgeRepository,
                             GraphMetaRepository graphMetaRepository,
                             OptimizationTaskRepository taskRepository,
                             SlamProperties properties,
                             ObjectMapper objectMapper,
                             Clock clock) {
        this.keyframeRepository = keyframeRepository;
        this.edgeRepository = edgeRepository;
        this.graphMetaRepository = graphMetaRepository;
        this.taskRepository = taskRepository;
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    public record KeyframeWriteResult(Keyframe keyframe, boolean deduped) {
    }

    public record EdgeWriteResult(GraphEdge edge, boolean deduped) {
    }

    /**
     * 写入关键帧。同 ID 同内容重复写入返回原记录；同 ID 改参或 requestId 冲突返回 409。
     */
    @Transactional
    public KeyframeWriteResult writeKeyframe(KeyframeRequest request) {
        String contentHash = ContentHashes.keyframeHash(
                request.source(), request.version(), request.x(), request.y(), request.theta(),
                request.measurement());

        // 行锁前置：串行化全部图写入，使随后的存在性检查与插入在同一临界区内，
        // 并发同内容写入会在锁内读到已提交记录并判重
        graphMetaRepository.lockAndGetCurrentVersion();

        Keyframe existing = keyframeRepository.findById(request.id());
        if (existing != null) {
            if (!existing.contentHash().equals(contentHash)) {
                throw new ConflictException(
                        "keyframe id '" + request.id() + "' already exists with different content");
            }
            return new KeyframeWriteResult(existing, true);
        }
        ensureRequestIdUnused(request.requestId(), request.id(), null);

        String measurementJson = ContentHashes.canonicalJson(request.measurement());
        graphMetaRepository.incrementVersion();
        int newVersion = graphMetaRepository.currentVersion();
        try {
            keyframeRepository.insert(new Keyframe(
                    request.id(),
                    request.source(),
                    request.version(),
                    request.x(),
                    request.y(),
                    request.theta(),
                    measurementJson,
                    request.requestId(),
                    contentHash,
                    newVersion,
                    Instant.now(clock)
            ));
        } catch (DuplicateKeyException e) {
            // 兜底：极端并发下唯一索引拦截重复 ID/requestId
            throw new ConflictException("duplicate keyframe id or requestId under concurrent write");
        }
        return new KeyframeWriteResult(keyframeRepository.findById(request.id()), false);
    }

    /**
     * 写入边。里程计边直接成为有效边；回环约束需通过版本化阈值校验，
     * 不通过时保存 REJECTED 审计记录且不递增图版本。
     */
    @Transactional
    public EdgeWriteResult writeEdge(EdgeRequest request) {
        EdgeType type;
        try {
            type = EdgeType.valueOf(request.type());
        } catch (IllegalArgumentException e) {
            throw new UnprocessableException("unknown edge type '" + request.type() + "'");
        }
        if (type == EdgeType.LOOP && (request.score() == null || request.residual() == null)) {
            throw new UnprocessableException("loop closure edge requires score and residual");
        }

        List<Double> covariance = request.covariance();
        if (covariance == null || covariance.size() != 36 || covariance.stream().anyMatch(d -> d == null)) {
            throw new UnprocessableException("covariance must contain exactly 36 non-null elements");
        }
        double covarianceTrace = covariance.get(0) + covariance.get(7) + covariance.get(14)
                + covariance.get(21) + covariance.get(28) + covariance.get(35);

        String contentHash = ContentHashes.edgeHash(
                type.name(), request.source(), request.version(), request.fromKeyframeId(),
                request.toKeyframeId(), request.dx(), request.dy(), request.dtheta(),
                covariance, request.score(), request.residual());

        // 行锁前置：串行化全部边写入（含 REJECTED 审计），使存在性检查、requestId 校验与插入互斥
        graphMetaRepository.lockAndGetCurrentVersion();

        GraphEdge existing = edgeRepository.findById(request.id());
        if (existing != null) {
            if (!existing.contentHash().equals(contentHash)) {
                throw new ConflictException(
                        "edge id '" + request.id() + "' already exists with different content");
            }
            return new EdgeWriteResult(existing, true);
        }
        ensureRequestIdUnused(request.requestId(), null, request.id());

        // 边只能引用已存在的关键帧
        Keyframe from = keyframeRepository.findById(request.fromKeyframeId());
        Keyframe to = keyframeRepository.findById(request.toKeyframeId());
        if (from == null || to == null) {
            throw new UnprocessableException("edge references unknown keyframe(s): "
                    + request.fromKeyframeId() + " -> " + request.toKeyframeId());
        }

        String covarianceJson = toJson(covariance);
        Double score = type == EdgeType.LOOP ? request.score() : null;
        Double residual = type == EdgeType.LOOP ? request.residual() : null;

        String rejectReason = type == EdgeType.LOOP
                ? validateLoop(request.version(), request.score(), request.residual(), covarianceTrace)
                : null;

        int graphVersion = 0;
        EdgeStatus status;
        if (rejectReason == null) {
            status = EdgeStatus.VALID;
            graphMetaRepository.incrementVersion();
            graphVersion = graphMetaRepository.currentVersion();
        } else {
            // 不可信回环：仅审计，不进入有效图，不递增图版本
            status = EdgeStatus.REJECTED;
        }

        try {
            edgeRepository.insert(new GraphEdge(
                    request.id(),
                    type,
                    request.source(),
                    request.version(),
                    request.fromKeyframeId(),
                    request.toKeyframeId(),
                    request.dx(),
                    request.dy(),
                    request.dtheta(),
                    covarianceJson,
                    score,
                    residual,
                    request.requestId(),
                    contentHash,
                    status,
                    rejectReason,
                    graphVersion,
                    Instant.now(clock)
            ));
        } catch (DuplicateKeyException e) {
            throw new ConflictException("duplicate edge id or requestId under concurrent write");
        } catch (DataIntegrityViolationException e) {
            // 并发删除等极端情况下关键帧引用失效
            throw new UnprocessableException("edge references unknown keyframe(s)");
        }
        return new EdgeWriteResult(edgeRepository.findById(request.id()), false);
    }

    /**
     * 按来源数据版本解析阈值并校验回环可信度；返回 null 表示通过，否则返回拒绝原因。
     */
    private String validateLoop(int version, double score, double residual, double covarianceTrace) {
        SlamProperties.Loop loop = properties.getLoop();
        double scoreMin = loop.getScoreMin();
        double residualMax = loop.getResidualMax();
        double traceMax = loop.getCovarianceTraceMax();
        SlamProperties.Thresholds override = loop.getVersionOverrides().get(version);
        if (override != null) {
            if (override.getScoreMin() != null) {
                scoreMin = override.getScoreMin();
            }
            if (override.getResidualMax() != null) {
                residualMax = override.getResidualMax();
            }
            if (override.getCovarianceTraceMax() != null) {
                traceMax = override.getCovarianceTraceMax();
            }
        }
        if (score < scoreMin) {
            return "loop score %.4f below threshold %.4f for version %d".formatted(score, scoreMin, version);
        }
        if (residual > residualMax) {
            return "loop residual %.4f above threshold %.4f for version %d".formatted(residual, residualMax, version);
        }
        if (covarianceTrace > traceMax) {
            return "covariance trace %.4f above threshold %.4f for version %d".formatted(covarianceTrace, traceMax, version);
        }
        return null;
    }

    /**
     * requestId 全局唯一：关键帧、边、优化任务共用同一幂等键空间。
     */
    private void ensureRequestIdUnused(String requestId, String currentKeyframeId, String currentEdgeId) {
        Keyframe kf = keyframeRepository.findByRequestId(requestId);
        if (kf != null && !kf.id().equals(currentKeyframeId)) {
            throw new ConflictException("requestId '" + requestId + "' already used by keyframe " + kf.id());
        }
        GraphEdge edge = edgeRepository.findByRequestId(requestId);
        if (edge != null && !edge.id().equals(currentEdgeId)) {
            throw new ConflictException("requestId '" + requestId + "' already used by edge " + edge.id());
        }
        if (taskRepository.existsByRequestId(requestId)) {
            throw new ConflictException("requestId '" + requestId + "' already used by an optimization task");
        }
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to serialize covariance", e);
        }
    }
}
