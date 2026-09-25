package com.example.starter.db;

import java.time.LocalDateTime;

/**
 * 通风作业持久化对象。作业窗为半开区间 [startTime, endTime)。
 */
public record VentilationOperationPo(
        long id,
        long siloId,
        long fanId,
        String requestId,
        String purpose,
        int roundNo,
        double ambientHumidity,
        LocalDateTime startTime,
        LocalDateTime endTime,
        String status,
        LocalDateTime startedAt,
        LocalDateTime completedAt,
        LocalDateTime createdAt
) {
}
