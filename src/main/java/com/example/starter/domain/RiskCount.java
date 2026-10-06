package com.example.starter.domain;

/**
 * 路段某风险等级的树木数量统计行。
 */
public record RiskCount(long segmentId, int riskLevel, long count) {
}
