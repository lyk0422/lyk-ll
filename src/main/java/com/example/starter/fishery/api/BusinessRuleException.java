package com.example.starter.fishery.api;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * 业务规则拒绝（如配额超限整笔拒绝、状态不允许、差异说明缺失），映射 422。
 */
@ResponseStatus(HttpStatus.UNPROCESSABLE_ENTITY)
public class BusinessRuleException extends RuntimeException {

    public BusinessRuleException(String message) {
        super(message);
    }
}
