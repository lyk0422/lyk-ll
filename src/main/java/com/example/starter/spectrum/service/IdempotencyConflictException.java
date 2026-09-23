package com.example.starter.spectrum.service;

import org.springframework.http.HttpStatus;

/**
 * 同一幂等键携带不同请求指纹，映射 409。
 */
public class IdempotencyConflictException extends LeaseServiceException {

    public IdempotencyConflictException(String requestId) {
        super("IDEMPOTENCY_CONFLICT", HttpStatus.CONFLICT,
                "requestId " + requestId + " was already used with different parameters");
    }
}
