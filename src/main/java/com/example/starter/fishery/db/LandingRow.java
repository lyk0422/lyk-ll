package com.example.starter.fishery.db;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 进港卸渔报告行。
 *
 * @param deviation 与日志累计量的最大鱼种偏差比例（百分比，如 3.00 表示 3%）
 * @param reviewTriggered 是否因偏差超过 3% 转人工复核
 */
public record LandingRow(
        Long id,
        String tripCode,
        Instant landedAt,
        BigDecimal totalKg,
        BigDecimal deviationPct,
        boolean reviewTriggered,
        String deviationExplanation,
        Instant createdAt) {
}
