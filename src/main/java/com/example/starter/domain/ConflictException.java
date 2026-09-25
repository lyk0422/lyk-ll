package com.example.starter.domain;

import org.springframework.http.HttpStatus;

/**
 * 409 冲突：同键异参、同日重复有效凭证、终态后修改、逾期争议等。
 */
public class ConflictException extends BusinessException {

    public ConflictException(String message) {
        super(HttpStatus.CONFLICT, message);
    }
}
