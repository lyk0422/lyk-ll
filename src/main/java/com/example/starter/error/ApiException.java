package com.example.starter.error;

/**
 * 携带稳定业务错误码的运行时异常，由全局异常处理器转换为统一错误响应。
 */
public class ApiException extends RuntimeException {

    private final ErrorCode errorCode;

    public ApiException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public ErrorCode errorCode() {
        return errorCode;
    }
}
