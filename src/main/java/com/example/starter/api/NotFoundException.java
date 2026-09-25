package com.example.starter.api;

/**
 * 资源不存在异常，映射 HTTP 404。
 */
public class NotFoundException extends RuntimeException {

    public NotFoundException(String message) {
        super(message);
    }
}
