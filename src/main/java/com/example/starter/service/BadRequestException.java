package com.example.starter.service;

/**
 * 请求参数非法（HTTP 400）：授权人相同、有效窗口起止非法等无法通过 Bean 校验表达的语义非法参数。
 */
public class BadRequestException extends BusinessException {

    public BadRequestException(String message) {
        super(message);
    }

    @Override
    public int status() {
        return 400;
    }
}
