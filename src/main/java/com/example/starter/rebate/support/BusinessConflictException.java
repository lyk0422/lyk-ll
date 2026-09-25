package com.example.starter.rebate.support;

/**
 * 业务规则冲突（含幂等同键异参 409、状态非法流转、唯一批次冲突等）。
 */
public class BusinessConflictException extends RuntimeException {
    public BusinessConflictException(String message) {
        super(message);
    }
}
