package com.example.starter.runway.dto;

/**
 * 统一错误报文。
 *
 * @param code    稳定错误码
 * @param message 错误说明
 */
public record ErrorResponse(String code, String message) {
}
