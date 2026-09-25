package com.example.starter.trial.service;

/**
 * 请求被业务规则拒绝（HTTP 422）：如单人重复确认、非授权人员、终态后修改分配等。
 */
public class BusinessRuleException extends RuntimeException {
    public BusinessRuleException(String message) {
        super(message);
    }
}
