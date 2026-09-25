package com.example.starter.service;

/**
 * 业务异常基类，携带 HTTP 状态码语义。
 */
public abstract class BusinessException extends RuntimeException {

    protected BusinessException(String message) {
        super(message);
    }

    /**
     * 对应的 HTTP 状态码。
     */
    public abstract int status();
}
