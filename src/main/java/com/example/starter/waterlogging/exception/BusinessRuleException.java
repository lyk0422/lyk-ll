package com.example.starter.waterlogging.exception;

import org.springframework.http.HttpStatus;

/**
 * 不满足业务规则（预警级别不足、启机间隔不足 10 分钟、未完成即归档等），对应 HTTP 422。
 */
public class BusinessRuleException extends ApiException {

    public BusinessRuleException(String code, String message) {
        super(HttpStatus.UNPROCESSABLE_ENTITY, code, message);
    }
}
