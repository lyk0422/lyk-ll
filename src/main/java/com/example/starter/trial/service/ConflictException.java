package com.example.starter.trial.service;

/**
 * 业务冲突（HTTP 409）：同键异参、重复入组等。
 */
public class ConflictException extends RuntimeException {
    public ConflictException(String message) {
        super(message);
    }
}
