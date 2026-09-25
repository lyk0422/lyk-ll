package com.example.starter.marpol.error;

import org.springframework.http.HttpStatus;

/**
 * 404：船舶、航次、记录或核查不存在。
 */
public class NotFoundException extends ApiException {

    public NotFoundException(String message) {
        super(HttpStatus.NOT_FOUND, message);
    }
}
