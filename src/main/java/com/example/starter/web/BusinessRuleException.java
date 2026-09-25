package com.example.starter.web;

import org.springframework.http.HttpStatus;

/**
 * 业务规则不满足（422）：湿度禁通风、轮次上限、状态不允许、作业窗重叠等。
 */
public class BusinessRuleException extends ApiException {

    public BusinessRuleException(String message) {
        super(HttpStatus.UNPROCESSABLE_ENTITY, message);
    }
}
