package com.example.starter.cutover;

import org.springframework.http.HttpStatus;

/**
 * 业务异常基类，携带 HTTP 状态码。
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
