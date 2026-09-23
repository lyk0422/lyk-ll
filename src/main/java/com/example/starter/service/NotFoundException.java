package com.example.starter.service;

import org.springframework.http.HttpStatus;

/** 请求资源不存在（如未知令牌族）。 */
public class NotFoundException extends ApiException {
    public NotFoundException(String message) {
        super(HttpStatus.NOT_FOUND, "NOT_FOUND", message);
    }
}
