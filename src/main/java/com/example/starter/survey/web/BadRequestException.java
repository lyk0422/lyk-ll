package com.example.starter.survey.web;

import org.springframework.http.HttpStatus;

/** 400：请求参数非法（集合内重复键、格式不合法等 bean validation 之外的参数错误）。 */
public class BadRequestException extends ApiException {
    public BadRequestException(String code, String message) {
        super(HttpStatus.BAD_REQUEST, code, message);
    }
}
