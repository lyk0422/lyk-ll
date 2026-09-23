package com.example.starter.ctlog.web;

import org.springframework.http.HttpStatus;

/**
 * 业务异常：携带稳定错误码与 HTTP 状态，供全局异常处理器统一返回。
 */
public class ApiException extends RuntimeException {

    private final String code;
    private final HttpStatus httpStatus;

    public ApiException(HttpStatus status, String code, String message) {
        super(message);
        this.httpStatus = status;
        this.code = code;
    }

    public String getCode() {
        return code;
    }

    public HttpStatus getHttpStatus() {
        return httpStatus;
    }
}
