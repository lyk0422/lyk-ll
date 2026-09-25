package com.example.starter.domain;

import java.time.Instant;

/**
 * requestId 幂等记录；指纹覆盖 requestId 与全部业务字段。
 *
 * @param requestId    请求幂等键
 * @param kind         请求类型
 * @param fingerprint  业务指纹（SHA-256）
 * @param lotNo        关联批次编号
 * @param httpStatus   首次响应 HTTP 状态码
 * @param responseBody 首次响应 JSON 正文（重放原样返回）
 * @param createdAt    首次受理时间（UTC）
 */
public record IdempotentRecord(
        String requestId,
        RequestKind kind,
        String fingerprint,
        String lotNo,
        int httpStatus,
        String responseBody,
        Instant createdAt
) {
}
