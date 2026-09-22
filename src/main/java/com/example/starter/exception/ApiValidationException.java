package com.example.starter.exception;

/**
 * 请求体语义非法（如位定义越界、多路复用配置矛盾），映射 HTTP 400。
 */
public class ApiValidationException extends RuntimeException {

    public ApiValidationException(String message) {
        super(message);
    }
}
