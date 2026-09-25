package com.example.starter.pension.web;

/**
 * 请求参数非法或不满足业务前置条件，映射为 HTTP 400。
 */
public class BadRequestException extends RuntimeException {

    public BadRequestException(String message) {
        super(message);
    }
}
