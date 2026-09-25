package com.example.starter.carbon.service;

/**
 * 同键异参冲突，映射 HTTP 409。
 */
public class IdempotencyConflictException extends RuntimeException {

    public IdempotencyConflictException(String message) {
        super(message);
    }
}
