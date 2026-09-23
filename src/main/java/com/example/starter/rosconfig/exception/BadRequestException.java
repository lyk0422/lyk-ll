package com.example.starter.rosconfig.exception;

/**
 * 400：请求体不合法。
 */
public class BadRequestException extends RuntimeException {

    public BadRequestException(String message) {
        super(message);
    }
}
