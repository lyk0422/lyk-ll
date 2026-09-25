package com.example.starter.survey.web;

import org.springframework.http.HttpStatus;

/** 409：幂等键同键异参冲突，或业务状态冲突。 */
public class ConflictException extends ApiException {
    public ConflictException(String code, String message) {
        super(HttpStatus.CONFLICT, code, message);
    }
}
