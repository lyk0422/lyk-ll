package com.example.starter.service;

import org.springframework.http.HttpStatus;

/**
 * 资源不存在异常（HTTP 404）：作业不存在等。
 */
public class NotFoundException extends ApiException {

    public NotFoundException(String message) {
        super(HttpStatus.NOT_FOUND, message);
    }
}
