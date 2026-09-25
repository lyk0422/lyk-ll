package com.example.starter.epidemic.api;

/**
 * 统一错误响应体。
 */
public record ErrorResponse(String error, String message) {
}
