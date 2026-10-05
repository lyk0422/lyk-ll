package com.example.starter.flood.web.dto;

import java.time.LocalDateTime;

/**
 * 处置链事件视图（预警级别提升等留痕）。
 */
public record WarningEventView(
        String eventType,
        LocalDateTime eventTime,
        String detail) {
}
