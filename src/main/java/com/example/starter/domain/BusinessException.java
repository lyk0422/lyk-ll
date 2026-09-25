package com.example.starter.domain;

import org.springframework.http.HttpStatus;

/**
 * 业务异常基类，映射为具体 HTTP 状态。
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
