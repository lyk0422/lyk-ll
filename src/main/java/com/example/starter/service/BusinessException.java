package com.example.starter.service;

/**
 * 业务规则异常：由统一异常处理映射为 409（规则冲突）或指定状态码。
 */
public class BusinessException extends RuntimeException {

    private final int statusCode;

    public BusinessException(String message) {
        this(409, message);
    }

    public BusinessException(int statusCode, String message) {
        super(message);
        this.statusCode = statusCode;
    }

    public int getStatusCode() {
        return statusCode;
    }
}
