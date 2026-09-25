package com.example.starter.radio.service;

/**
 * 业务冲突异常，映射 HTTP 409：幂等同键异参、并发校准会话冲突、单人重复核对等。
 */
public class ConflictException extends RuntimeException {

    public ConflictException(String message) {
        super(message);
    }
}
