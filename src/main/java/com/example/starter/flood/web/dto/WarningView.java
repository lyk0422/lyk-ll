package com.example.starter.flood.web.dto;

import java.time.LocalDateTime;

/**
 * 处置链视图。
 */
public record WarningView(
        String warningNo,
        String pondCode,
        String title,
        String rainSummary,
        String status,
        String requestId,
        LocalDateTime openedAt,
        LocalDateTime archivedAt) {
}
