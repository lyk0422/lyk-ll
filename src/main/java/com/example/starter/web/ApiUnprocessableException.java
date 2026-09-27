package com.example.starter.web;

import org.springframework.http.HttpStatus;

/**
 * 业务规则拒绝（计划缺段/重复机组/超装机容量、目标不可实现、绑定过期版本等），映射 HTTP 422。
 */
public class ApiUnprocessableException extends RuntimeException {

    public ApiUnprocessableException(String message) {
        super(message);
    }

    public HttpStatus status() {
        return HttpStatus.UNPROCESSABLE_ENTITY;
    }
}
