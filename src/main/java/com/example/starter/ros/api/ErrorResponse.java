package com.example.starter.ros.api;

import java.time.Instant;

/**
 * 统一错误响应体。
 *
 * @param errorCode 业务错误码
 * @param message   错误说明
 * @param path      请求路径
 * @param timestamp 错误产生时间（UTC）
 */
public record ErrorResponse(
        String errorCode,
        String message,
        String path,
        Instant timestamp) {
}
