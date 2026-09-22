package com.example.starter.domain;

/**
 * 业务前置条件不满足（轨迹不存在、轨迹已回收、门限外等），映射为 HTTP 400/404。
 */
public class BusinessException extends RuntimeException {

    private final String code;

    public BusinessException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
