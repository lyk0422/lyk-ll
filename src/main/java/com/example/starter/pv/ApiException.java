package com.example.starter.pv;

import org.springframework.http.HttpStatus;

/**
 * 业务异常：携带 HTTP 状态语义，由全局异常处理器转换为错误响应。
 */
public class ApiException extends RuntimeException {

    private final HttpStatus status;

    private ApiException(HttpStatus status, String message) {
        super(message);
        this.status = status;
    }

    public HttpStatus status() {
        return status;
    }

    /** 400：请求参数或业务字段不合法（如迟报缺少迟报原因）。 */
    public static ApiException badRequest(String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, message);
    }

    /** 404：资源不存在。 */
    public static ApiException notFound(String message) {
        return new ApiException(HttpStatus.NOT_FOUND, message);
    }

    /** 409：状态冲突或幂等键冲突（如同键异参、关闭条件不满足）。 */
    public static ApiException conflict(String message) {
        return new ApiException(HttpStatus.CONFLICT, message);
    }
}
