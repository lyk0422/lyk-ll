package com.example.starter.feed.error;

/**
 * 幂等键冲突：同一 requestId 携带不同业务参数（指纹不一致），返回 409 且不改变任何状态。
 */
public class IdempotencyConflictException extends RuntimeException {

    public IdempotencyConflictException(String requestId) {
        super("requestId=" + requestId + " 已用于不同的业务参数，请求被拒绝（409）");
    }
}
