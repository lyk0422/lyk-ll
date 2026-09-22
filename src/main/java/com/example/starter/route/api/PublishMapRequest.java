package com.example.starter.route.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 发布一个全新地图版本的请求载荷。热更新只能发布新版本，载荷为该版本不可变快照。
 *
 * @param version  地图版本号
 * @param nodes    端点（节点）快照
 * @param segments 路段（有向拓扑）快照
 */
public record PublishMapRequest(
        @NotBlank @Size(max = 64) String version,
        @NotEmpty @Valid List<NodeSpec> nodes,
        @NotEmpty @Valid List<SegmentSpec> segments) {

    /**
     * 端点定义。
     */
    public record NodeSpec(
            @NotBlank @Size(max = 64) String nodeId,
            @NotNull Double x,
            @NotNull Double y) {
    }

    /**
     * 有向路段定义：fromNodeId 指向 toNodeId。
     *
     * @param valid 路段是否有效；缺省视为有效（true）
     */
    public record SegmentSpec(
            @NotBlank @Size(max = 64) String segmentId,
            @NotBlank @Size(max = 64) String fromNodeId,
            @NotBlank @Size(max = 64) String toNodeId,
            @PositiveOrZero Double lengthM,
            Boolean valid) {
    }
}
