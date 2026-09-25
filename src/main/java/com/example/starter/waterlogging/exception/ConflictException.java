package com.example.starter.waterlogging.exception;

import org.springframework.http.HttpStatus;

/**
 * 同 requestId 指纹冲突、分区序列占用等并发/冲突语义，对应 HTTP 409。
 */
public class ConflictException extends ApiException {

    public ConflictException(String code, String message) {
        super(HttpStatus.CONFLICT, code, message);
    }
}
