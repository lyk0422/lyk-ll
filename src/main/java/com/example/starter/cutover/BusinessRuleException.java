package com.example.starter.cutover;

import org.springframework.http.HttpStatus;

/** 422：不满足业务规则（状态非法、窗口超限、审批冲突等）。 */
public class BusinessRuleException extends ApiException {
    public BusinessRuleException(String message) {
        super(HttpStatus.UNPROCESSABLE_ENTITY, message);
    }
}
