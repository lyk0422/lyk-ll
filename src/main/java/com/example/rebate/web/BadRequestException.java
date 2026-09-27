package com.example.rebate.web;

/**
 * 400 请求参数非法（如取值不在允许集合内）。
 */
public class BadRequestException extends ApiException {

    public BadRequestException(String message) {
        super(400, message);
    }
}
