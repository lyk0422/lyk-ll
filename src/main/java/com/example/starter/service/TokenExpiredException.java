package com.example.starter.service;

import org.springframework.http.HttpStatus;

/** 令牌已到期：now &gt;= expiresAt。 */
public class TokenExpiredException extends ApiException {
    public TokenExpiredException(String message) {
        super(HttpStatus.UNAUTHORIZED, "TOKEN_EXPIRED", message);
    }
}
