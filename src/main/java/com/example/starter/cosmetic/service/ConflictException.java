package com.example.starter.cosmetic.service;

/**
 * 业务规则冲突异常，映射为 HTTP 409：同键异参、状态不允许的操作等。
 */
public class ConflictException extends RuntimeException {

    /** 冲突错误码（机器可读）。 */
    private final String code;

    public ConflictException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
