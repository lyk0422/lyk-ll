package com.example.starter.model;

import java.time.LocalDateTime;

/**
 * 同类缺陷复发聚合行：90 日窗口内按缺陷类别统计。
 */
public record RecurrenceAgg(
        String defectType,
        long count,
        LocalDateTime firstDetectedAt,
        LocalDateTime lastDetectedAt) {
}
