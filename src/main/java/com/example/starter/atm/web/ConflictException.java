package com.example.starter.atm.web;

import org.springframework.http.HttpStatus;

/** 409 冲突：状态非法流转、同键异参、唯一受理冲突等。 */
public class ConflictException extends ApiException {
    public ConflictException(String message) {
        super(HttpStatus.CONFLICT, message);
    }
}
