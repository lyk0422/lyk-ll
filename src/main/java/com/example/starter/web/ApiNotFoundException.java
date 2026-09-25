package com.example.starter.web;

import org.springframework.http.HttpStatus;

/**
 * 资源不存在，映射 HTTP 404。
 */
public class ApiNotFoundException extends RuntimeException {

    public ApiNotFoundException(String message) {
        super(message);
    }

    public HttpStatus status() {
        return HttpStatus.NOT_FOUND;
    }
}
