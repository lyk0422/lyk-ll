package com.example.starter.cems.web.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 超标标记视图（日均值严格大于日均限值才生成，恰等限值判达标不生成）。
 */
public record MarkView(
        Long id,
        String outfallCode,
        String requestId,
        LocalDateTime dayStart,
        BigDecimal dailyAvg,
        BigDecimal limitValue,
        String status,
        LocalDateTime createdAt) {
}
