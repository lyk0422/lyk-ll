package com.example.starter.api;

/**
 * 业务冲突异常，映射 HTTP 409：幂等键冲突、重复送检、非法状态流转等。
 */
public class ConflictException extends RuntimeException {

    public ConflictException(String message) {
        super(message);
    }
}
