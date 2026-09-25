package com.example.starter.error;

import org.springframework.http.HttpStatus;

/**
 * 业务异常：携带 HTTP 状态码与业务错误码，由全局异常处理器统一转换为响应。
 */
public class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final String code;

    public ApiException(HttpStatus status, String code, String message) {
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
