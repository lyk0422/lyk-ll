package com.example.starter.signing.service;

import org.springframework.http.HttpStatus;

/**
 * 业务规则异常：携带 HTTP 状态码与稳定错误码，由全局异常处理器转换为响应。
 */
public class BusinessRuleException extends RuntimeException {

    private final HttpStatus status;
    private final String code;

    public BusinessRuleException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public HttpStatus getStatus() {
        return status;
    }

    public String getCode() {
        return code;
    }
}
