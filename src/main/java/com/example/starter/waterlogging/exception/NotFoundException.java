package com.example.starter.waterlogging.exception;

import org.springframework.http.HttpStatus;

/**
 * 引用对象（泵站、预降单等）不存在，对应 HTTP 404。
 */
public class NotFoundException extends ApiException {

    public NotFoundException(String code, String message) {
        super(HttpStatus.NOT_FOUND, code, message);
    }
}
