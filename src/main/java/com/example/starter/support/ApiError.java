package com.example.starter.support;

/**
 * 统一错误响应体：code 为稳定机器码，message 为可读说明（仅业务消息，不含堆栈）。
 */
public record ApiError(String code, String message) {
}
