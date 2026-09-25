package com.example.starter.service;

import org.springframework.http.HttpStatus;

/**
 * 冲突异常（HTTP 409）：幂等键同键异参、同一股道并发接入唯一受理冲突等。
 */
public class ConflictException extends ApiException {

    public ConflictException(String message) {
        super(HttpStatus.CONFLICT, message);
    }
}
