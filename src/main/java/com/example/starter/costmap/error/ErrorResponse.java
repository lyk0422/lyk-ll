package com.example.starter.costmap.error;

/**
 * 统一错误响应体。
 *
 * @param code    稳定错误码（见 {@link ErrorCode}）
 * @param message 面向调用方的错误描述
 */
public record ErrorResponse(String code, String message) {
}
