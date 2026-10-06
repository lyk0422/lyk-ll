package com.example.starter.dto;

import java.time.LocalDateTime;

/**
 * 限功率链视图：active 表示当前生效。
 */
public record PowerLimitView(
        long id,
        long bladeId,
        String reason,
        LocalDateTime startedAt,
        LocalDateTime revokedAt,
        boolean active) {
}
