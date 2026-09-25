package com.example.starter.gas.web;

import java.time.OffsetDateTime;

/**
 * 统一错误响应体。
 */
public record ErrorResponse(String errorCode, String message, OffsetDateTime timestamp) {
}
