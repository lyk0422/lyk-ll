package com.example.starter.cutover;

import org.springframework.http.HttpStatus;

/** 409：同键异参冲突。 */
public class ConflictException extends ApiException {
    public ConflictException(String message) {
        super(HttpStatus.CONFLICT, message);
    }
}
