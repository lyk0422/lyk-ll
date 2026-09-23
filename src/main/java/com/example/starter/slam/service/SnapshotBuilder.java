package com.example.starter.slam.service;

import com.example.starter.slam.domain.GraphEdge;
import com.example.starter.slam.domain.Keyframe;
import com.example.starter.slam.opt.EdgeConstraint;
import com.example.starter.slam.opt.NodePose;
import com.example.starter.slam.opt.OptimizationInput;
import com.example.starter.slam.repo.EdgeRepository;
import com.example.starter.slam.repo.KeyframeRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 构建与解析优化输入快照。快照包含某图版本下全部有效关键帧先验位姿与有效边，
 * 任务重试必须复用建任务时的同一快照。
 */
@Component
public class SnapshotBuilder {

    private final KeyframeRepository keyframeRepository;
    private final EdgeRepository edgeRepository;
    private final ObjectMapper objectMapper;

    public SnapshotBuilder(KeyframeRepository keyframeRepository,
                          EdgeRepository edgeRepository,
                          ObjectMapper objectMapper) {
        this.keyframeRepository = keyframeRepository;
        this.edgeRepository = edgeRepository;
        this.objectMapper = objectMapper;
    }

    /**
     * 构建指定图版本的快照 JSON 与摘要。
     */
    public SnapshotData build(int graphVersion) {
        List<NodePose> nodes = keyframeRepository.findAsOfVersion(graphVersion).stream()
                .map(kf -> new NodePose(kf.id(), kf.x(), kf.y(), kf.theta()))
                .toList();
        List<EdgeConstraint> edges = edgeRepository.findValidAsOfVersion(graphVersion).stream()
                .map(this::toConstraint)
                .toList();
        Snapshot snapshot = new Snapshot(graphVersion, nodes, edges);
        String json = writeJson(snapshot);
        String digest = ContentHashes.sha256(json);
        return new SnapshotData(json, digest);
    }

    /**
     * 从任务保存的原快照恢复优化器输入（重试路径）。
     */
    public OptimizationInput parse(String snapshotJson, String snapshotDigest) {
        Snapshot snapshot;
        try {
            snapshot = objectMapper.readValue(snapshotJson, Snapshot.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("stored optimization snapshot is unreadable", e);
        }
        return new OptimizationInput(snapshot.graphVersion(), snapshotDigest,
                snapshot.keyframes(), snapshot.edges());
    }

    private EdgeConstraint toConstraint(GraphEdge edge) {
        List<Double> covariance;
        try {
            covariance = objectMapper.readValue(edge.covarianceJson(), new TypeReference<>() {
            });
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("stored covariance is unreadable", e);
        }
        return new EdgeConstraint(edge.id(), edge.fromKeyframeId(), edge.toKeyframeId(),
                edge.dx(), edge.dy(), edge.dtheta(), covariance);
    }

    private String writeJson(Snapshot snapshot) {
        try {
            return objectMapper.writeValueAsString(snapshot);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to serialize snapshot", e);
        }
    }

    /**
     * 快照持久化结构。
     */
    public record Snapshot(int graphVersion, List<NodePose> keyframes, List<EdgeConstraint> edges) {
    }

    /**
     * 快照 JSON 与摘要。
     */
    public record SnapshotData(String json, String digest) {
    }
}
