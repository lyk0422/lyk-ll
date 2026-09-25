package com.example.starter.db;

import java.time.LocalDateTime;

/**
 * 复测记录持久化对象。
 */
public record RetestPo(
        long id,
        long siloId,
        long operationId,
        String requestId,
        int roundNo,
        double maxTemperature,
        double minTemperature,
        double gradient,
        boolean passed,
        LocalDateTime measuredAt,
        LocalDateTime createdAt
) {
}
