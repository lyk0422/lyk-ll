package com.example.starter.error;

/**
 * 资源不存在，映射 HTTP 404。
 */
public class NotFoundException extends RuntimeException {

    public NotFoundException(String message) {
        super(message);
    }
}
