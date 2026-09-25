package com.example.starter.atm.web;

import org.springframework.http.HttpStatus;

/** 422：业务规则不满足，如超容量、押运员被冻结、挂账逾期等。 */
public class UnprocessableException extends ApiException {
    public UnprocessableException(String message) {
        super(HttpStatus.UNPROCESSABLE_ENTITY, message);
    }
}
