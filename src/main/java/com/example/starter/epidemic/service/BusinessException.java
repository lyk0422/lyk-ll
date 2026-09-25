package com.example.starter.epidemic.service;

import org.springframework.http.HttpStatus;

/**
 * 业务异常基类，携带 HTTP 状态码与稳定错误码。
 */
public class BusinessException extends RuntimeException {

    private final String errorCode;
    private final HttpStatus status;

    public BusinessException(String errorCode, String message, HttpStatus status) {
        super(message);
        this.errorCode = errorCode;
        this.status = status;
    }

    public String getErrorCode() {
        return errorCode;
    }

    public HttpStatus getStatus() {
        return status;
    }
}
