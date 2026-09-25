package com.example.starter.marpol.error;

import org.springframework.http.HttpStatus;

/**
 * 409：幂等键冲突、并发版本冲突、终态核查被修改等。
 */
public class ConflictException extends ApiException {

    public ConflictException(String message) {
        super(HttpStatus.CONFLICT, message);
    }
}
