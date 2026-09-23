package com.example.starter.coldchain.exception;

import org.springframework.http.HttpStatus;

/**
 * 409：冲突。包括同序号改值、同 requestId 异参、版本冲突、重复建件等。
 */
public class ConflictException extends ApiException {

    public ConflictException(String message) {
        super(HttpStatus.CONFLICT, message);
    }
}
