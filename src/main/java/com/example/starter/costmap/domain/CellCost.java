package com.example.starter.costmap.domain;

/**
 * 单个格栅的代价。
 */
public record CellCost(int x, int y, int cost) {
}
