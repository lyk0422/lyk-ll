package com.example.starter.rebate.support;

/**
 * 请求前置条件不满足（如批次缺退税率版本、疑点未解除等）。
 */
public class BusinessRuleException extends RuntimeException {
    public BusinessRuleException(String message) {
        super(message);
    }
}
