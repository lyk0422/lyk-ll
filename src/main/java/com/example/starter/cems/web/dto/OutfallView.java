package com.example.starter.cems.web.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 排口视图。totalBaseline 为限排生效同事务冻结的排放总量基数，null 表示尚未冻结。
 */
public record OutfallView(
        String outfallCode,
        String outfallName,
        String status,
        BigDecimal dailyLimit,
        BigDecimal hourlyLimit,
        BigDecimal totalBaseline,
        LocalDateTime baselineFrozenAt,
        LocalDateTime registeredAt,
        LocalDateTime updatedAt,
        boolean archived) {
}
