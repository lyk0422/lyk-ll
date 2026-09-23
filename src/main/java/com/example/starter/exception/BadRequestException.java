package com.example.starter.exception;

/**
 * 请求参数或状态不合法时抛出。
 */
public class BadRequestException extends RuntimeException {

    public BadRequestException(String message) {
        super(message);
    }
}
