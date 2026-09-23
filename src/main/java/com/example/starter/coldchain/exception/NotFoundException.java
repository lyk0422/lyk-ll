package com.example.starter.coldchain.exception;

import org.springframework.http.HttpStatus;

/**
 * 404：货件或运输段不存在。
 */
public class NotFoundException extends ApiException {

    public NotFoundException(String message) {
        super(HttpStatus.NOT_FOUND, message);
    }
}
