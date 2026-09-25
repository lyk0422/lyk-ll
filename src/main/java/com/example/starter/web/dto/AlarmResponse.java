package com.example.starter.web.dto;

import java.time.LocalDateTime;

/**
 * 告警判定结果。
 */
public record AlarmResponse(
        String requestId,
        String siloCode,
        boolean alarmed,
        double maxTemperature,
        double minTemperature,
        double safeTemperature,
        LocalDateTime measuredAt,
        String state
) {
}
