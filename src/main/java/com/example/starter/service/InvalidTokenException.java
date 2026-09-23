package com.example.starter.service;

import org.springframework.http.HttpStatus;

/** 令牌无效（不存在、格式非法或无法识别）。 */
public class InvalidTokenException extends ApiException {
    public InvalidTokenException(String message) {
        super(HttpStatus.UNAUTHORIZED, "INVALID_TOKEN", message);
    }
}
