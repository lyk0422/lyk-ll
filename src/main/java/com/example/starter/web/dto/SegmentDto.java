package com.example.starter.web.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 路段发布输入。
 *
 * @param segmentId 路段业务标识，版本内唯一
 * @param startNode 起点拓扑节点标识
 * @param endNode   终点拓扑节点标识
 * @param startX    起点坐标 X，单位米
 * @param startY    起点坐标 Y，单位米
 * @param endX      终点坐标 X，单位米
 * @param endY      终点坐标 Y，单位米
 * @param active    有效状态
 */
public record SegmentDto(
        @NotBlank String segmentId,
        @NotBlank String startNode,
        @NotBlank String endNode,
        double startX,
        double startY,
        double endX,
        double endY,
        boolean active) {
}
