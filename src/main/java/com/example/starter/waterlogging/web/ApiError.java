package com.example.starter.waterlogging.web;

import java.time.LocalDateTime;

/**
 * 统一错误响应体。
 */
public record ApiError(String code, String message, LocalDateTime timestamp) {
}
