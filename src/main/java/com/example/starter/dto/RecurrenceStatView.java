package com.example.starter.dto;

import java.time.LocalDateTime;

/**
 * 同类缺陷复发统计视图：按缺陷类别聚合，窗口可复算。
 */
public record RecurrenceStatView(
        String defectType,
        long count,
        LocalDateTime windowStart,
        LocalDateTime windowEnd,
        LocalDateTime firstDetectedAt,
        LocalDateTime lastDetectedAt) {
}
