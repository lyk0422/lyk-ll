package com.example.starter.service;

/**
 * 资源不存在（批次号等），映射为 HTTP 404。
 */
public class NotFoundException extends RuntimeException {

    public NotFoundException(String message) {
        super(message);
    }
}
