package com.example.starter.flood.web.dto;

import java.time.LocalDateTime;

/**
 * 错误响应体。
 */
public record ApiError(
        String code,
        String message,
        LocalDateTime time) {
}
