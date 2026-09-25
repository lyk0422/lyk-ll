package com.example.starter.dye.web;

import org.springframework.http.HttpStatus;

/**
 * 422 业务规则拒绝：称量偏差超差、并缸配方不一致等。
 */
public class BusinessRuleException extends ApiException {

    public BusinessRuleException(String message) {
        super(HttpStatus.UNPROCESSABLE_ENTITY, message);
    }
}
