package com.example.starter.wbb.exception;

/**
 * 违反载重平衡业务规则（超重、重心越界、冻结后修改、状态非法流转等）：返回 422。
 */
public class BusinessRuleException extends RuntimeException {
    public BusinessRuleException(String message) {
        super(message);
    }
}
