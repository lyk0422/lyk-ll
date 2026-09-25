package com.example.starter.blast.api;

import java.time.Instant;

/**
 * 错误响应体。
 *
 * @param timestamp 错误发生时刻（UTC）
 * @param status    HTTP 状态码
 * @param error     错误类型
 * @param message   错误描述
 * @param path      请求路径
 */
public record ErrorResponse(
        Instant timestamp,
        int status,
        String error,
        String message,
        String path) {
}
