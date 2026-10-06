package com.example.starter.waste;

import java.time.LocalDateTime;

/**
 * 偏差异常核查单实体行，结论字段未核查时为 null。
 */
public record ExceptionCase(
        long id,
        long manifestId,
        String status,
        String conclusionType,
        String conclusionNote,
        String conclusionRequestId,
        String conclusionFingerprint,
        LocalDateTime createdAt,
        LocalDateTime resolvedAt) {

    public static final String STATUS_OPEN = "OPEN";
    public static final String STATUS_RESOLVED = "RESOLVED";
}
