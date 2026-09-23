package com.example.starter.service;

import org.springframework.http.HttpStatus;

/** 令牌族已撤销（登出或重放检测），族内任何令牌不得再轮换。 */
public class FamilyRevokedException extends ApiException {
    public FamilyRevokedException(String message) {
        super(HttpStatus.FORBIDDEN, "FAMILY_REVOKED", message);
    }
}
