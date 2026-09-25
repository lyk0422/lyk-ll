package com.example.starter.quarantine.service;

/**
 * 同键异参冲突：HTTP 409，且不改变单据状态。
 */
public class IdempotencyConflictException extends RuntimeException {

    public IdempotencyConflictException(String message) {
        super(message);
    }
}
