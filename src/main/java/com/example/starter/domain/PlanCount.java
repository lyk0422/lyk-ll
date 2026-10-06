package com.example.starter.domain;

/**
 * 路段某状态的修剪计划数量统计行。
 */
public record PlanCount(long segmentId, String status, long count) {
}
