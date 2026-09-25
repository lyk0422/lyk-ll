package com.example.starter.reinsurance.api;

import java.time.Instant;

/**
 * 统一错误响应体。
 */
public record ErrorResponse(
        Instant timestamp,
        int status,
        String error,
        String code,
        String message,
        String path) {
}
