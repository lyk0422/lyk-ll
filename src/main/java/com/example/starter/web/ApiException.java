package com.example.starter.web;

/**
 * 业务异常：携带 HTTP 状态码，由全局异常处理器转为统一错误响应。
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

    /**
     * 409 冲突：同键异参、状态冲突、并发竞态等。
     */
    public static ApiException conflict(String message) {
        return new ApiException(409, message);
    }

    /**
     * 422 无法处理：业务规则不满足（如补正未覆盖全部缺项）。
     */
    public static ApiException unprocessable(String message) {
        return new ApiException(422, message);
    }

    /**
     * 404 不存在。
     */
    public static ApiException notFound(String message) {
        return new ApiException(404, message);
    }
}
