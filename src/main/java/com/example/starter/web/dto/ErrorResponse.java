package com.example.starter.web.dto;

/**
 * 统一错误响应。
 *
 * @param error   错误类别
 * @param message 错误描述
 */
public record ErrorResponse(String error, String message) {
}
