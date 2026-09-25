package com.example.starter.slaughter.web;

import org.springframework.http.HttpStatus;

/**
 * 业务可处理异常，携带对应 HTTP 状态码与稳定错误码。
 */
public class BusinessException extends RuntimeException {

    private final HttpStatus status;
    private final String code;

    public BusinessException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public HttpStatus getStatus() {
        return status;
    }

    public String getCode() {
        return code;
    }
}
