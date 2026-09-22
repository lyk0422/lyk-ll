package com.example.starter.orchestrator;

/**
 * 同一 requestId 以不同参数重复提交，映射 HTTP 409。
 */
public class IdempotencyConflictException extends RuntimeException {
    public IdempotencyConflictException(String message) {
        super(message);
    }
}
