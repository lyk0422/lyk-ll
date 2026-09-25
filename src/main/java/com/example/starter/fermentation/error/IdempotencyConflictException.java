package com.example.starter.fermentation.error;

import org.springframework.http.HttpStatus;

/**
 * 幂等冲突：同一 requestId 携带与首次受理不同的业务参数，返回 409 且不改变任何业务状态。
 */
public class IdempotencyConflictException extends ApiException {

    public IdempotencyConflictException(String requestId) {
        super(HttpStatus.CONFLICT,
                "requestId '" + requestId + "' 已被不同参数的请求占用，本次请求未生效");
    }
}
