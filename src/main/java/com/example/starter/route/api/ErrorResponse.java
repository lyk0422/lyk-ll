package com.example.starter.route.api;

/**
 * 统一错误响应体。
 */
public record ErrorResponse(String errorCode, String message) {
}
