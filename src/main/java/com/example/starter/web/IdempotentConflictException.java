package com.example.starter.web;

/**
 * 请求与已受理幂等记录指纹不一致（同键异参），映射 HTTP 409，且不得改变任何阀门状态。
 */
public class IdempotentConflictException extends RuntimeException {

    public IdempotentConflictException(String requestId) {
        super("idempotent conflict for requestId=" + requestId);
    }
}
