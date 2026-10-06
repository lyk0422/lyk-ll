package com.example.starter.trace.dto;

/**
 * 统一错误响应体。
 */
public record ErrorResponse(String code, String message) {
}
