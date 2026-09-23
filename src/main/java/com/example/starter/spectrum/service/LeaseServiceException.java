package com.example.starter.spectrum.service;

import org.springframework.http.HttpStatus;

/**
 * 业务异常基类，携带 HTTP 状态码与稳定错误码。
 */
public class LeaseServiceException extends RuntimeException {

    private final String code;
    private final HttpStatus status;

    /**
     * @param code    稳定错误码
     * @param status  映射的 HTTP 状态
     * @param message 面向调用方的错误说明
     */
    public LeaseServiceException(String code, HttpStatus status, String message) {
        super(message);
        this.code = code;
        this.status = status;
    }

    public String getCode() {
        return code;
    }

    public HttpStatus getStatus() {
        return status;
    }
}
