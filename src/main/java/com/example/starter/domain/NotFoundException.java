package com.example.starter.domain;

import org.springframework.http.HttpStatus;

/** 404 资源不存在。 */
public class NotFoundException extends BusinessException {

    public NotFoundException(String message) {
        super(HttpStatus.NOT_FOUND, message);
    }
}
