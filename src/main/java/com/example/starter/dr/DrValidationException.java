package com.example.starter.dr;

import org.springframework.http.HttpStatus;

/**
 * 请求参数或业务前置条件不满足（400）。
 */
public class DrValidationException extends DrBusinessException {

    public DrValidationException(String message) {
        super(HttpStatus.BAD_REQUEST, message);
    }
}
