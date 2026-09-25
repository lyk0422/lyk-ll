package com.example.starter.flood.exception;

import org.springframework.http.HttpStatus;

/**
 * 业务异常：携带 HTTP 状态码与稳定错误码，由统一异常处理器转换为响应体。
 */
public class FloodControlException extends RuntimeException {

    private final String code;
    private final HttpStatus status;

    public FloodControlException(String code, String message, HttpStatus status) {
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

    public static FloodControlException notFound(String message) {
        return new FloodControlException("NOT_FOUND", message, HttpStatus.NOT_FOUND);
    }

    public static FloodControlException conflict(String message) {
        return new FloodControlException("IDEMPOTENT_CONFLICT", message, HttpStatus.CONFLICT);
    }

    public static FloodControlException business(String code, String message) {
        return new FloodControlException(code, message, HttpStatus.UNPROCESSABLE_ENTITY);
    }
}
