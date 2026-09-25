package com.example.starter.flood.web.dto;

import java.time.LocalDateTime;

/**
 * 复盘归档结果视图。
 */
public record ReviewView(
        String warningNo,
        String status,
        String note,
        boolean replay,
        LocalDateTime archivedAt) {
}
