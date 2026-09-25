package com.example.starter.roaming.web;

/**
 * 统一错误响应体。
 *
 * @param errorCode 机器可读错误码
 * @param message   错误说明
 */
public record ErrorResponse(String errorCode, String message) {
}
