package com.example.starter.gas;

import java.time.Instant;

/**
 * 统一错误响应体。
 */
public record ErrorResponse(String code, String message, Instant timestamp) {
}
