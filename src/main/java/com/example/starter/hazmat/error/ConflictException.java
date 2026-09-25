package com.example.starter.hazmat.error;

/**
 * 业务规则冲突（HTTP 409）：幂等同键异参、终态竞态冲突、状态机非法迁移等。
 */
public class ConflictException extends RuntimeException {

    public ConflictException(String message) {
        super(message);
    }
}
