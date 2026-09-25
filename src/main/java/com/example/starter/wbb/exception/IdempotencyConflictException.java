package com.example.starter.wbb.exception;

/**
 * 同 requestId 指纹不一致（同键异参）：返回 409，且不产生新版本/新记录。
 */
public class IdempotencyConflictException extends RuntimeException {
    public IdempotencyConflictException(String requestId) {
        super("requestId " + requestId + " 已用于不同的业务参数");
    }
}
