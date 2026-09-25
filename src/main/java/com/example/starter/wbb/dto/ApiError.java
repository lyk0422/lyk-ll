package com.example.starter.wbb.dto;

/**
 * 统一错误响应体。
 */
public record ApiError(String code, String message) {
}
