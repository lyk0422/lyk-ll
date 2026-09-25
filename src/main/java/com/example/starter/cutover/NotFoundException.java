package com.example.starter.cutover;

import org.springframework.http.HttpStatus;

/** 404：申请不存在。 */
public class NotFoundException extends ApiException {
    public NotFoundException(String message) {
        super(HttpStatus.NOT_FOUND, message);
    }
}
