package com.example.starter.domain;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 小时评估结果。exceeded 仅在某污染物均值严格大于限值时为 TRUE（恰等于判达标）。
 */
public record HourlyAssessment(
        Long id,
        Long planId,
        LocalDateTime hourStart,
        BigDecimal noxAvg,
        BigDecimal so2Avg,
        BigDecimal dustAvg,
        int readingCount,
        boolean exceeded,
        String pollutants,
        int consecutiveNo) {
}
