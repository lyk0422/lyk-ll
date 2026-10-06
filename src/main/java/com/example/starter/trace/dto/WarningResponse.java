package com.example.starter.trace.dto;

import java.time.LocalDateTime;

/**
 * 蜂场预警视图：同蜂场同花期累计恰 3 批疑似触发。
 */
public record WarningResponse(
        Long id,
        Long apiaryId,
        String apiaryCode,
        String apiaryName,
        String flowerPeriod,
        Integer suspectedCount,
        LocalDateTime createdAt) {
}
