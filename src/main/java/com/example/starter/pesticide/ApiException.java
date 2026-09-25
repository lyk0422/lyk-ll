package com.example.starter.pesticide;

import org.springframework.http.HttpStatus;

/**
 * 业务异常：携带 HTTP 状态码，由全局异常处理器转换为错误响应。
 */
public class ApiException extends RuntimeException {

    private final HttpStatus status;

    public ApiException(HttpStatus status, String message) {
        super(message);
        this.status = status;
    }

    public HttpStatus status() {
        return status;
    }

    /**
     * 构造 409 冲突异常（幂等冲突、状态冲突、重复送检等）。
     */
    public static ApiException conflict(String message) {
        return new ApiException(HttpStatus.CONFLICT, message);
    }

    /**
     * 构造 404 不存在异常。
     */
    public static ApiException notFound(String message) {
        return new ApiException(HttpStatus.NOT_FOUND, message);
    }
}
