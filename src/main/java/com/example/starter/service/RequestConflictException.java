package com.example.starter.service;

import org.springframework.http.HttpStatus;

/** 请求冲突：同请求键但令牌或客户端不同，返回 409。 */
public class RequestConflictException extends ApiException {
    public RequestConflictException(String message) {
        super(HttpStatus.CONFLICT, "REQUEST_CONFLICT", message);
    }
}
