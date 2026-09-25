package com.example.starter.service;

/**
 * 请求不满足业务前置条件（如复检逾期、越级发证、标签不足），映射为 HTTP 422。
 */
public class BusinessRuleException extends RuntimeException {

    public BusinessRuleException(String message) {
        super(message);
    }
}
