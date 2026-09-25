package com.example.starter.reinsurance.common;

/**
 * 业务异常；携带 HTTP 状态码与错误码，由全局异常处理器转换为响应。
 */
public class ApiException extends RuntimeException {

    private final int httpStatus;
    private final ErrorCode errorCode;

    public ApiException(int httpStatus, ErrorCode errorCode, String message) {
        super(message);
        this.httpStatus = httpStatus;
        this.errorCode = errorCode;
    }

    public ApiException(ErrorCode errorCode, String message) {
        this(errorCode.statusValue(), errorCode, message);
    }

    public int getHttpStatus() {
        return httpStatus;
    }

    public ErrorCode getErrorCode() {
        return errorCode;
    }
}
