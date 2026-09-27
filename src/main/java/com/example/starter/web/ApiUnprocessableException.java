package com.example.starter.web;

import org.springframework.http.HttpStatus;

/**
 * 业务规则拒绝（目标不可实现、绑定过期计划版本等），映射 HTTP 422。
 */
public class ApiUnprocessableException extends RuntimeException {

    public ApiUnprocessableException(String message) {
        super(message);
    }

    public HttpStatus status() {
        return HttpStatus.UNPROCESSABLE_ENTITY;
    }
}
