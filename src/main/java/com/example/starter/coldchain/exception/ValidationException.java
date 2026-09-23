package com.example.starter.coldchain.exception;

import org.springframework.http.HttpStatus;

/**
 * 422：请求参数语义不合法（时间倒退、缺号、保管方不匹配、间隔超限补写等）。
 */
public class ValidationException extends ApiException {

    public ValidationException(String message) {
        super(HttpStatus.UNPROCESSABLE_ENTITY, message);
    }
}
