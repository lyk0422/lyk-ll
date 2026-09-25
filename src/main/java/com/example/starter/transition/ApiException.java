package com.example.starter.transition;

/**
 * 业务异常，携带 HTTP 状态码；由全局异常处理转换为响应。
 */
public class ApiException extends RuntimeException {

    private final int status;

    public ApiException(int status, String message) {
        super(message);
        this.status = status;
    }

    public static ApiException conflict(String message) {
        return new ApiException(409, message);
    }

    public static ApiException notFound(String message) {
        return new ApiException(404, message);
    }

    public static ApiException badRequest(String message) {
        return new ApiException(400, message);
    }

    public int getStatus() {
        return status;
    }
}
