package com.example.starter.costmap.web.dto;

/**
 * 单元格级差异项：快照代价与当前视图（或另一快照）代价不同。
 *
 * @param x            列坐标
 * @param y            行坐标
 * @param snapshotCost 快照固化代价
 * @param liveCost     当前查询视图代价
 */
public record DiffEntry(int x, int y, int snapshotCost, int liveCost) {
}
