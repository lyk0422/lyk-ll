package com.example.starter.support;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;

/**
 * 时间解析：支持带偏移的 ISO-8601（如 2026-09-26T10:00:00+08:00 / ...Z）；
 * 不带偏移的本地时间按 Asia/Shanghai 解释。
 */
public final class TimeParser {

    private TimeParser() {
    }

    public static Instant parse(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        String t = text.trim();
        try {
            return OffsetDateTime.parse(t).toInstant();
        } catch (DateTimeParseException ignored) {
            // 继续尝试本地时间
        }
        try {
            return LocalDateTime.parse(t).atZone(BusinessClock.ZONE).toInstant();
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("时间格式应为 ISO-8601: " + text);
        }
    }
}
