package com.example.starter.model;

import java.time.LocalDateTime;

/**
 * 根因评估行：status IN_PROGRESS/COMPLETED。
 */
public record AssessmentRow(
        long id,
        long bladeId,
        String defectType,
        int triggerCount,
        String status,
        LocalDateTime startedAt,
        LocalDateTime completedAt) {
}
