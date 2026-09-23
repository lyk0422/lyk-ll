package com.example.starter.exception;

/**
 * 422：业务规则违例（非法状态迁移、expectedVersion 不匹配、同操作异参复用请求键等）。
 */
public class BusinessRuleException extends RuntimeException {
    public BusinessRuleException(String message) {
        super(message);
    }
}
