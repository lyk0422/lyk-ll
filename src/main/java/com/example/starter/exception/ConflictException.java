package com.example.starter.exception;

/** 业务规则冲突，映射 HTTP 409（幂等指纹冲突、重复核销、重复调账等）。 */
public class ConflictException extends RuntimeException {
    public ConflictException(String message) {
        super(message);
    }
}
