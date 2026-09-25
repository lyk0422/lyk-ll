package com.example.starter.service;

/**
 * 业务规则异常：携带 HTTP 状态码与错误码，由统一异常处理转换为响应。
 */
public class BusinessException extends RuntimeException {

    private final int httpStatus;
    private final String error;

    public BusinessException(int httpStatus, String error, String message) {
        super(message);
        this.httpStatus = httpStatus;
        this.error = error;
    }

    public int getHttpStatus() {
        return httpStatus;
    }

    public String getError() {
        return error;
    }
}
