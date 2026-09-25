package com.example.starter.web.dto;

/**
 * 统一错误响应体。
 */
public record ApiError(String code, String message) {
}
