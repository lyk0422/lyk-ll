package com.example.starter.survey.web;

import org.springframework.http.HttpStatus;

/** 400：参数非法（重复项目代码、空集合、非必需项目子集等语义级参数错误）。 */
public class BadRequestException extends ApiException {
    public BadRequestException(String code, String message) {
        super(HttpStatus.BAD_REQUEST, code, message);
    }
}
