package com.example.starter.error;

/**
 * 请求参数不合法（业务层面），映射 HTTP 400。
 */
public class BadRequestException extends RuntimeException {

    public BadRequestException(String message) {
        super(message);
    }
}
