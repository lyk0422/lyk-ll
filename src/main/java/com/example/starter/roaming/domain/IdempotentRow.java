package com.example.starter.roaming.domain;

import java.time.LocalDateTime;

/**
 * 幂等请求记录行。
 */
public record IdempotentRow(
        Long id,
        String requestId,
        String requestKind,
        String paramFingerprint,
        int httpStatus,
        String responseBody,
        LocalDateTime createdAt) {
}
