package com.example.starter.wb.domain;

import java.time.LocalDateTime;

/**
 * 幂等请求首次结果快照（idempotent_request）。
 */
public record IdempotentRow(
        String requestId,
        String fingerprint,
        int httpStatus,
        String resultJson,
        LocalDateTime createdAt
) {
}
