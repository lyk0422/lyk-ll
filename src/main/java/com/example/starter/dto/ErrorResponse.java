package com.example.starter.dto;

import java.time.Instant;

/** 统一错误响应体。 */
public record ErrorResponse(String code, String message, Instant timestamp) {
}
