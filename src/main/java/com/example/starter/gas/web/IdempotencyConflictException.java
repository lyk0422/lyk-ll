package com.example.starter.gas.web;

import org.springframework.http.HttpStatus;

/**
 * 同 requestId 异参数：返回 409，且不改变任何阀门/任务状态。
 */
public class IdempotencyConflictException extends ApiException {

    public IdempotencyConflictException(String message) {
        super(HttpStatus.CONFLICT, "IDEMPOTENCY_CONFLICT", message);
    }
}
