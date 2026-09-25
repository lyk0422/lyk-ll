package com.example.starter.legality.service;

/**
 * 同键异参冲突：同一 requestId 已存在，但机组成员、航班或报到时刻与本次不一致。映射 HTTP 409，且不写入记录。
 */
public class IdempotentConflictException extends RuntimeException {

    public IdempotentConflictException(String requestId) {
        super("idempotent key conflict: " + requestId);
    }
}
