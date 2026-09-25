package com.example.starter.cems.web.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 监测读数视图。late=true 表示归档后迟到数据，仅记录迟到事件、未纳入统计。
 */
public record ReadingView(
        Long id,
        String outfallCode,
        String periodType,
        LocalDateTime periodStart,
        BigDecimal avgValue,
        boolean late,
        LocalDateTime createdAt) {
}
