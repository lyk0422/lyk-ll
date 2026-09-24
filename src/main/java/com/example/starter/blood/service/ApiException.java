package com.example.starter.blood.service;

import org.springframework.http.HttpStatus;

/**
 * 业务异常：携带 HTTP 状态码，由全局异常处理器转换为错误响应。
 */
public class ApiException extends RuntimeException {

    private final HttpStatus status;

    public ApiException(HttpStatus status, String message) {
        super(message);
        this.status = status;
    }

    /**
     * 对应的 HTTP 状态码。
     *
     * @return HTTP 状态码
     */
    public HttpStatus status() {
        return status;
    }
}
