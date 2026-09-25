package com.example.starter.lc.dto;

/**
 * 统一错误响应体。
 *
 * @param errorCode 业务错误码
 * @param message   错误描述
 */
public record ErrorResponse(String errorCode, String message) {
}
