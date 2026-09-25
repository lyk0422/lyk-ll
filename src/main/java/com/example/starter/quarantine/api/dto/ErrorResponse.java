package com.example.starter.quarantine.api.dto;

import java.time.Instant;

/**
 * 统一错误响应体。
 */
public record ErrorResponse(Instant timestamp, int status, String error, String message, String path) {
}
