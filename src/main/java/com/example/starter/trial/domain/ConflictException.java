package com.example.starter.trial.domain;

/**
 * 业务冲突：同键异参（409）、筛选号已入组、重复揭盲不同参数等。
 */
public class ConflictException extends RuntimeException {
    public ConflictException(String message) {
        super(message);
    }
}
