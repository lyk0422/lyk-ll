package com.example.starter.dye.web;

import org.springframework.http.HttpStatus;

/**
 * 409 冲突：同键异参、染缸并发占用等。
 */
public class ConflictException extends ApiException {

    public ConflictException(String message) {
        super(HttpStatus.CONFLICT, message);
    }
}
