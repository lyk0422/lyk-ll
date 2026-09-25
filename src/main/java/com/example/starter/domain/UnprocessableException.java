package com.example.starter.domain;

import org.springframework.http.HttpStatus;

/** 422 业务前提不满足（如读数不足、表底非单调）。 */
public class UnprocessableException extends BusinessException {

    public UnprocessableException(String message) {
        super(HttpStatus.UNPROCESSABLE_ENTITY, message);
    }
}
