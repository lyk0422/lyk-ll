package com.example.starter.rolling.service;

import org.springframework.http.HttpStatus;

/**
 * 业务前置条件不满足：状态流转非法、资源不存在等（默认 400/404）。
 */
public class BusinessException extends RuntimeException {

    private final HttpStatus status;

    public BusinessException(HttpStatus status, String message) {
        super(message);
        this.status = status;
    }

    public HttpStatus getStatus() {
        return status;
    }
}
