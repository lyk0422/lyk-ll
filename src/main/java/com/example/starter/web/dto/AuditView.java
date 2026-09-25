package com.example.starter.web.dto;

import java.time.Instant;

/**
 * 认证留痕视图。
 */
public record AuditView(
        String action,
        String detail,
        String operator,
        Instant createdAt
) {
}
