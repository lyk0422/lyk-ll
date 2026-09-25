package com.example.starter.trial.api.dto;

/**
 * 统一错误响应体。
 */
public record ErrorResponse(String error, String message) {
}
