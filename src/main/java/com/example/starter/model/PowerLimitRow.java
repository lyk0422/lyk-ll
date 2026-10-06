package com.example.starter.model;

import java.time.LocalDateTime;

/**
 * 限功率链行：revokedAt 为 null 表示仍生效。
 */
public record PowerLimitRow(
        long id,
        long bladeId,
        String reason,
        LocalDateTime startedAt,
        LocalDateTime revokedAt) {

    public boolean active() {
        return revokedAt == null;
    }
}
