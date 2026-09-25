package com.example.starter.survey.domain;

import java.time.Instant;

/**
 * 幂等记录：requestId 为键，指纹覆盖 requestId 与全部业务字段。
 *
 * @param id           主键
 * @param requestId    幂等键，唯一
 * @param fingerprint  首次请求的业务指纹
 * @param httpStatus   首次响应 HTTP 状态码
 * @param responseBody 首次响应体 JSON（同键同参原样重放）
 * @param createdAt    首次受理时刻（UTC）
 */
public record IdempotentRecord(
        Long id,
        String requestId,
        String fingerprint,
        int httpStatus,
        String responseBody,
        Instant createdAt
) {
}
