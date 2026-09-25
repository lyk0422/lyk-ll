package com.example.starter.wb.domain;

import java.time.LocalDateTime;

/**
 * 双岗复核确认行（review_confirmation）。
 */
public record ReviewRow(
        Long id,
        long manifestId,
        String requestId,
        String fingerprint,
        String roleCode,
        String reviewer,
        LocalDateTime createdAt
) {
}
