package com.example.starter.portability.model;

import java.time.Instant;

/**
 * 幂等记录，对应 idempotency_record 表。
 * 指纹覆盖 requestId 与全部业务字段；同键同参重放首次结果，同键异参返回 409。
 */
public record IdempotencyRecord(
        String requestId,
        String endpoint,
        String fingerprint,
        int httpStatus,
        String responseBody,
        Long flowId,
        Instant createdAt) {
}
