package com.example.starter.support;

import org.springframework.http.HttpStatus;

/**
 * 业务异常：携带 HTTP 状态码与错误信息，由全局异常处理统一转换为响应体。
 */
public class ApiException extends RuntimeException {

    private final HttpStatus status;

    public ApiException(HttpStatus status, String message) {
        super(message);
        this.status = status;
    }

    public HttpStatus getStatus() {
        return status;
    }
}
