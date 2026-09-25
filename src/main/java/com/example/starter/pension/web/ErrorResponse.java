package com.example.starter.pension.web;

/**
 * 统一错误响应体。
 *
 * @param code    机器可读错误码
 * @param message 错误说明
 */
public record ErrorResponse(String code, String message) {
}
