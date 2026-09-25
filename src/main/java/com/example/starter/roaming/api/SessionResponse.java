package com.example.starter.roaming.api;

import java.time.Instant;

/**
 * 会话启动/结束通用结果。
 */
public record SessionResponse(
        String sessionNo,
        String status,
        Instant startTime,
        Instant endTime,
        java.math.BigDecimal totalEnergyKwh,
        boolean replayed) {
}
