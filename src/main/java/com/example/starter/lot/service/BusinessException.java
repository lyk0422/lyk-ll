package com.example.starter.lot.service;

import org.springframework.http.HttpStatus;

/**
 * 业务异常基类：携带 HTTP 状态码与稳定错误码。
 */
public class BusinessException extends RuntimeException {

    private final HttpStatus httpStatus;
    private final String code;

    public BusinessException(HttpStatus httpStatus, String code, String message) {
        super(message);
        this.httpStatus = httpStatus;
        this.code = code;
    }

    public HttpStatus getHttpStatus() {
        return httpStatus;
    }

    public String getCode() {
        return code;
    }
}
