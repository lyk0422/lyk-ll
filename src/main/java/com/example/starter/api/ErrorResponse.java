package com.example.starter.api;

import java.time.Instant;

/**
 * 统一错误响应体。
 *
 * @param status    HTTP 状态码
 * @param error     错误码
 * @param message   错误说明
 * @param timestamp 错误产生时间（UTC）
 */
public record ErrorResponse(
        int status,
        String error,
        String message,
        Instant timestamp
) {
}
