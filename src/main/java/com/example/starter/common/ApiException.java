package com.example.starter.common;

/**
 * 业务异常：携带 HTTP 状态码、业务错误码与可选明细，由全局异常处理器转换为响应体。
 */
public class ApiException extends RuntimeException {

    private final int status;
    private final String code;
    private final transient Object details;

    public ApiException(int status, String code, String message) {
        this(status, code, message, null);
    }

    public ApiException(int status, String code, String message, Object details) {
        super(message);
        this.status = status;
        this.code = code;
        this.details = details;
    }

    public int getStatus() {
        return status;
    }

    public String getCode() {
        return code;
    }

    public Object getDetails() {
        return details;
    }

    public static ApiException badRequest(String code, String message) {
        return new ApiException(400, code, message);
    }

    public static ApiException notFound(String code, String message) {
        return new ApiException(404, code, message);
    }

    public static ApiException conflict(String code, String message) {
        return new ApiException(409, code, message);
    }

    public static ApiException conflict(String code, String message, Object details) {
        return new ApiException(409, code, message, details);
    }
}
