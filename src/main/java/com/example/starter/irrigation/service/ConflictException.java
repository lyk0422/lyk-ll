package com.example.starter.irrigation.service;

/**
 * 业务冲突：同键异参重放、同轮同组已有生效版本等场景，映射 HTTP 409。
 */
public class ConflictException extends RuntimeException {

    public ConflictException(String message) {
        super(message);
    }
}
