package com.example.starter.portability.dto;

/**
 * 统一错误响应。
 *
 * @param code    业务错误码
 * @param message 错误描述
 */
public record ErrorResponse(String code, String message) {
}
