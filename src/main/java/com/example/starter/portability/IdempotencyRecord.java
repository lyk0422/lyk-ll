package com.example.starter.portability;

import java.time.Instant;

/**
 * 幂等记录实体，对应 idempotency_record 表。
 * 指纹覆盖 requestId 与全部业务字段：同键同参重放首次结果，同键异参判 409。
 */
public record IdempotencyRecord(
        String endpoint,
        String requestId,
        String fingerprint,
        int responseStatus,
        String responseBody,
        Long flowId,
        Instant createdAt) {
}
