package com.example.starter.exception;

/**
 * 404：申请或快照不存在。
 */
public class NotFoundException extends RuntimeException {
    public NotFoundException(String message) {
        super(message);
    }
}
