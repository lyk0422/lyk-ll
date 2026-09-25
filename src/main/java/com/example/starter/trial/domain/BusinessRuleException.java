package com.example.starter.trial.domain;

/**
 * 请求被业务规则拒绝（422）：中心不存在、授权人无效、两名确认人相同、终态修改等。
 */
public class BusinessRuleException extends RuntimeException {
    public BusinessRuleException(String message) {
        super(message);
    }
}
