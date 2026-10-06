package com.example.starter.dto;

import java.time.LocalDateTime;

/**
 * 根因评估视图。
 */
public record AssessmentView(
        long id,
        long bladeId,
        String defectType,
        int triggerCount,
        String status,
        LocalDateTime startedAt,
        LocalDateTime completedAt,
        boolean powerLimitActive) {
}
