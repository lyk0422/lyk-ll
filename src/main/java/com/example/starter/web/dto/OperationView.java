package com.example.starter.web.dto;

import java.time.LocalDateTime;

/**
 * 通风作业窗视图。
 */
public record OperationView(
        long operationId,
        String requestId,
        String fanCode,
        String purpose,
        int roundNo,
        double ambientHumidity,
        LocalDateTime startTime,
        LocalDateTime endTime,
        String status,
        LocalDateTime startedAt,
        LocalDateTime completedAt
) {
}
