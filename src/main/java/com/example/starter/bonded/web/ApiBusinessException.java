package com.example.starter.bonded.web;

import org.springframework.http.HttpStatus;

/**
 * 业务异常基类，携带对应 HTTP 状态码。
 */
public class ApiBusinessException extends RuntimeException {

    private final HttpStatus status;

    public ApiBusinessException(HttpStatus status, String message) {
        super(message);
        this.status = status;
    }

    public HttpStatus getStatus() {
        return status;
    }
}
