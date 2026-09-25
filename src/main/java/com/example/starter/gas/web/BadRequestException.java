package com.example.starter.gas.web;

import org.springframework.http.HttpStatus;

/**
 * 请求参数语义错误（如路径与请求体门站编码不一致）。
 */
public class BadRequestException extends ApiException {

    public BadRequestException(String message) {
        super(HttpStatus.BAD_REQUEST, "BAD_REQUEST", message);
    }
}
