package com.example.starter.rolling.service;

import org.springframework.http.HttpStatus;

/**
 * 业务冲突异常：同键异参（409）、并发下达失败（409）等。
 */
public class ConflictException extends RuntimeException {

    private final HttpStatus status;

    public ConflictException(HttpStatus status, String message) {
        super(message);
        this.status = status;
    }

    public HttpStatus getStatus() {
        return status;
    }
}
