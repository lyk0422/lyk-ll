package com.example.starter.survey.web;

import org.springframework.http.HttpStatus;

/** 400：参数非法（Bean 校验之外的结构性参数错误，如重复项目代码、空集合）。 */
public class BadRequestException extends ApiException {
    public BadRequestException(String code, String message) {
        super(HttpStatus.BAD_REQUEST, code, message);
    }
}
