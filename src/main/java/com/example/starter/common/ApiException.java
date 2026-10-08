package com.example.starter.common;

/**
 * 业务异常，携带 HTTP 状态码；由全局异常处理器转换为统一错误响应。
 */
public class ApiException extends RuntimeException {

    private final int status;

    public ApiException(int status, String message) {
        super(message);
        this.status = status;
    }

    public int status() {
        return status;
    }

    public static ApiException notFound(String message) {
        return new ApiException(404, message);
    }

    public static ApiException conflict(String message) {
        return new ApiException(409, message);
    }

    public static ApiException badRequest(String message) {
        return new ApiException(400, message);
    }
}
