package com.example.rebate.web;

/**
 * 业务异常基类，携带 HTTP 状态码语义。
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
