package com.example.starter.web.dto;

import java.time.LocalDateTime;

/**
 * 通风令受理结果。
 */
public record VentilationResponse(
        String requestId,
        long operationId,
        String siloCode,
        String fanCode,
        String purpose,
        int roundNo,
        double ambientHumidity,
        LocalDateTime startTime,
        LocalDateTime endTime,
        String status,
        LocalDateTime startedAt
) {
}
