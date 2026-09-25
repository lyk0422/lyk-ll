package com.example.starter.web;

/**
 * 业务规则校验失败（设定点越窗、昼夜温差超限、变更间隔不足、预算不足等），映射为 HTTP 422。
 */
public class BusinessRuleException extends RuntimeException {

    public BusinessRuleException(String message) {
        super(message);
    }
}
