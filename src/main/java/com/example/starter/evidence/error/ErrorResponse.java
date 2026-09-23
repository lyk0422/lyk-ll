package com.example.starter.evidence.error;

/**
 * 统一错误响应体。
 *
 * @param code    稳定错误码，供调用方程序化判断
 * @param message 面向调用方的错误描述
 */
public record ErrorResponse(String code, String message) {
}
