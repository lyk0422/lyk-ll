package com.example.starter.trajectory.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 道路边界，按顺序排列的闭合多边形顶点（首尾无需重复）。
 * 多边形边上的轨迹点视为合法；点在外部且超出道路容差才判定失败。
 *
 * @param polygon 道路边界多边形顶点，单位米，至少三个顶点
 */
public record RoadBoundary(@NotNull @Size(min = 3, message = "道路边界至少需要三个顶点") List<Point> polygon) {
}
