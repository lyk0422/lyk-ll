package com.example.rebate.web;

/**
 * 400 请求参数非法（如驳回未填原因、金额非正整数分）。
 */
public class BadRequestException extends ApiException {

    public BadRequestException(String message) {
        super(400, message);
    }
}
