package com.example.starter.dye.web;

import org.springframework.http.HttpStatus;

/**
 * 业务异常基类：携带 HTTP 状态码与可读消息。
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
