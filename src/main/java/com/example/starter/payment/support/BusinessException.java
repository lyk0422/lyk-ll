package com.example.starter.payment.support;

import org.springframework.http.HttpStatus;

/**
 * 业务异常：由全局异常处理器映射为指定 HTTP 状态码与错误消息。
 */
public class BusinessException extends RuntimeException {

    private final HttpStatus status;

    public BusinessException(HttpStatus status, String message) {
        super(message);
        this.status = status;
    }

    public HttpStatus getStatus() {
        return status;
    }
}
