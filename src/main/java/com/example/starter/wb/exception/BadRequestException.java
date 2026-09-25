package com.example.starter.wb.exception;

/**
 * 请求格式或枚举值不合法（HTTP 400）。
 */
public class BadRequestException extends RuntimeException {
    public BadRequestException(String message) {
        super(message);
    }
}
