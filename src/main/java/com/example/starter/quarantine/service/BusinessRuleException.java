package com.example.starter.quarantine.service;

/**
 * 业务前置条件不满足（未找到单据、状态不允许、存栏不足、超量等）：HTTP 422。
 */
public class BusinessRuleException extends RuntimeException {

    public BusinessRuleException(String message) {
        super(message);
    }
}
