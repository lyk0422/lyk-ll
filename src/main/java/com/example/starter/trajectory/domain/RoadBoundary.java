package com.example.starter.trajectory.domain;

import java.util.List;

/**
 * 道路边界，按顺序给出的闭合多边形顶点（无需重复首点）。
 * 多边形内部与边界上的轨迹点均视为在道路内。
 *
 * @param vertices 多边形顶点，单位米，至少 3 个
 */
public record RoadBoundary(List<Vec> vertices) {
}
