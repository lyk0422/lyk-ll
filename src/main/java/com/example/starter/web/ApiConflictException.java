package com.example.starter.web;

import org.springframework.http.HttpStatus;

/**
 * 业务冲突：同键异参、风场已有执行中指令等，映射 HTTP 409。
 */
public class ApiConflictException extends RuntimeException {

    public ApiConflictException(String message) {
        super(message);
    }

    public HttpStatus status() {
        return HttpStatus.CONFLICT;
    }
}
