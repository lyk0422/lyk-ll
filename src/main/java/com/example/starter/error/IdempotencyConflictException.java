package com.example.starter.error;

/**
 * 幂等冲突：同 requestId 但业务字段指纹不一致，映射 HTTP 409。
 */
public class IdempotencyConflictException extends RuntimeException {

    public IdempotencyConflictException(String message) {
        super(message);
    }
}
