package com.example.starter.survey.web;

import org.springframework.http.HttpStatus;

/** 422：业务规则不满足（窗口外、重大缺陷未整改、逾期、不可展期等）。 */
public class BusinessRuleException extends ApiException {
    public BusinessRuleException(String code, String message) {
        super(HttpStatus.UNPROCESSABLE_ENTITY, code, message);
    }
}
