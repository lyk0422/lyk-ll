package com.example.starter.domain;

import java.time.LocalDateTime;

/**
 * 修剪计划行；closedAt/closedOnTime 为 NULL 表示未闭环。
 */
public record PlanRow(long id, long treeId, long segmentId, int riskLevel, PlanStatus status,
                      LocalDateTime createdAt, LocalDateTime deadline,
                      LocalDateTime closedAt, Boolean closedOnTime) {
}
