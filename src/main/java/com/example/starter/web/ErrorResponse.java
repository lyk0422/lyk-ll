package com.example.starter.web;

import java.time.Instant;

/**
 * 统一错误响应体。
 *
 * @param code      机器可读错误码
 * @param message   错误说明
 * @param requestId 触发异常的幂等键（可空）
 * @param occurredAt 异常发生时刻(UTC)
 */
public record ErrorResponse(
        String code,
        String message,
        String requestId,
        Instant occurredAt
) {
}
