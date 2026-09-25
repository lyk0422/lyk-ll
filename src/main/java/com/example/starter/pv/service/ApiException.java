package com.example.starter.pv.service;

import org.springframework.http.HttpStatus;

/**
 * 业务可预期错误，携带对应 HTTP 状态码。
 */
public class ApiException extends RuntimeException {

    private final HttpStatus status;

    public ApiException(HttpStatus status, String message) {
        super(message);
        this.status = status;
    }

    public HttpStatus getStatus() {
        return status;
    }
}
