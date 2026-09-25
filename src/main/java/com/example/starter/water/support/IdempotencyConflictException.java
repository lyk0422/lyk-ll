package com.example.starter.water.support;

/**
 * 同 requestId 异参冲突，映射为 HTTP 409，且不产生新版本。
 */
public class IdempotencyConflictException extends RuntimeException {

    public IdempotencyConflictException(String message) {
        super(message);
    }
}
