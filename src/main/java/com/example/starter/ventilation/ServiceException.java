package com.example.starter.ventilation;

import org.springframework.http.HttpStatus;

/**
 * 业务异常基类：携带对客户端可见的 HTTP 状态码与错误说明。
 */
public class ServiceException extends RuntimeException {

    private final HttpStatus status;

    public ServiceException(HttpStatus status, String message) {
        super(message);
        this.status = status;
    }

    public HttpStatus getStatus() {
        return status;
    }
}
