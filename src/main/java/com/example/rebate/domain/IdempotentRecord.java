package com.example.rebate.domain;

import java.time.Instant;

/**
 * requestId 幂等记录。指纹覆盖 requestId 与全部业务字段。
 *
 * @param fingerprint 业务参数指纹（SHA-256 十六进制）
 * @param responseStatus 首次响应 HTTP 状态码
 * @param responseBody 首次响应体（JSON）
 */
public record IdempotentRecord(
        String requestId,
        String opType,
        String fingerprint,
        Integer responseStatus,
        String responseBody,
        Instant createdAt,
        Instant completedAt) {
}
