package com.example.starter.service;

/**
 * 业务规则冲突（如同键异参、状态不允许的操作），映射为 HTTP 409。
 */
public class ConflictException extends RuntimeException {

    public ConflictException(String message) {
        super(message);
    }
}
