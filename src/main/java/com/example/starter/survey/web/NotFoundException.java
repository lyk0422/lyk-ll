package com.example.starter.survey.web;

import org.springframework.http.HttpStatus;

/** 404：资源不存在。 */
public class NotFoundException extends ApiException {
    public NotFoundException(String code, String message) {
        super(HttpStatus.NOT_FOUND, code, message);
    }
}
