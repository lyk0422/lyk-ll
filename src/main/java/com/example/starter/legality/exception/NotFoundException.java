package com.example.starter.legality.exception;

/**
 * 引用资源不存在（HTTP 404）。
 */
public class NotFoundException extends RuntimeException {

    public NotFoundException(String message) {
        super(message);
    }
}
