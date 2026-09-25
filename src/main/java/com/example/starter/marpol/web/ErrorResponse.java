package com.example.starter.marpol.web;

/**
 * 统一错误响应体。
 */
public record ErrorResponse(String error, String message) {
}
