package com.example.starter.slaughter.persistence;

import java.time.Instant;

/**
 * 幂等请求记录：保存首次成功响应及其指纹。
 */
public record IdempotentRecord(
        String requestId,
        String operation,
        String fingerprint,
        int httpStatus,
        String responseBody,
        Instant createdAtUtc) {
}
