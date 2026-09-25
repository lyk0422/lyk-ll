package com.example.starter.irrigation.service;

/**
 * 请求引用的业务资源不存在，映射 HTTP 404。
 */
public class NotFoundException extends RuntimeException {

    public NotFoundException(String message) {
        super(message);
    }
}
