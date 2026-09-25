package com.example.starter.web;

/**
 * 业务异常基类；由 {@link GlobalExceptionHandler} 统一转 HTTP 响应。
 */
public class ApiException extends RuntimeException {

    private final int status;

    public ApiException(int status, String message) {
        super(message);
        this.status = status;
    }

    public int getStatus() {
        return status;
    }
}
