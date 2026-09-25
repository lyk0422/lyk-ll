package com.example.starter.atm.web;

import org.springframework.http.HttpStatus;

/** 403：责任人已被冻结，禁止接新押运打卡任务。 */
public class ForbiddenException extends ApiException {
    public ForbiddenException(String message) {
        super(HttpStatus.FORBIDDEN, message);
    }
}
