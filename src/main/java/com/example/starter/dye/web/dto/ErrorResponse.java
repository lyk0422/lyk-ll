package com.example.starter.dye.web.dto;

import java.time.LocalDateTime;

/**
 * 统一错误响应体。
 */
public record ErrorResponse(
        LocalDateTime timestamp,
        int status,
        String error,
        String message) {
}
