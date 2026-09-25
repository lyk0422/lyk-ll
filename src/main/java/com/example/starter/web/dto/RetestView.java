package com.example.starter.web.dto;

import java.time.LocalDateTime;

/**
 * 复测对比视图（两轮复测可复算）。
 */
public record RetestView(
        long retestId,
        String requestId,
        long operationId,
        int roundNo,
        double maxTemperature,
        double minTemperature,
        double gradient,
        boolean passed,
        LocalDateTime measuredAt
) {
}
