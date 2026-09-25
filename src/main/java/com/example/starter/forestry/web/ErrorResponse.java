package com.example.starter.forestry.web;

/**
 * 统一错误响应体。
 */
public record ErrorResponse(String errorCode, String message) {
}
