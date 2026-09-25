package com.example.starter.web;

import org.springframework.http.HttpStatus;

/**
 * 冲突（409）：条码重复入库、同键异参的幂等冲突等。
 */
public class ConflictException extends ApiException {

    public ConflictException(String message) {
        super(HttpStatus.CONFLICT, "CONFLICT", message);
    }
}
