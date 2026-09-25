package com.example.starter.epidemic.service;

import org.springframework.http.HttpStatus;

/**
 * 幂等冲突（409）：同一 requestId 携带与首次请求不同的业务参数。
 */
public class IdempotencyConflictException extends ApiException {
    public IdempotencyConflictException(String message) {
        super(HttpStatus.CONFLICT, message);
    }
}
