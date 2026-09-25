package com.example.starter.roaming.web;

/**
 * 业务异常：携带 HTTP 状态码与机器可读错误码。
 */
public class ApiException extends RuntimeException {

    private final int status;
    private final String errorCode;

    public ApiException(int status, String errorCode, String message) {
        super(message);
        this.status = status;
        this.errorCode = errorCode;
    }

    public int getStatus() {
        return status;
    }

    public String getErrorCode() {
        return errorCode;
    }
}
