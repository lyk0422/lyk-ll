package com.example.starter.web;

import org.springframework.http.HttpStatus;

/**
 * 冲突（409）：幂等指纹不一致、并发下达落败、风机被占用等。
 */
public class ConflictException extends ApiException {

    public ConflictException(String message) {
        super(HttpStatus.CONFLICT, message);
    }
}
