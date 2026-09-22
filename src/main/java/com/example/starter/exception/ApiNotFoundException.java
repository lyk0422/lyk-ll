package com.example.starter.exception;

/**
 * 引用的资源不存在（如未知 DBC 版本），映射 HTTP 404。
 */
public class ApiNotFoundException extends RuntimeException {

    public ApiNotFoundException(String message) {
        super(message);
    }
}
