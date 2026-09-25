package com.example.starter.common;

import org.springframework.http.HttpStatus;

/**
 * 业务异常：携带 HTTP 状态码，由全局异常处理器转为统一错误响应。
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
     * 409 冲突：幂等键参数不一致、状态冲突、并发竞态失败等。
     */
    public static ApiException conflict(String message) {
        return new ApiException(HttpStatus.CONFLICT, message);
    }

    /**
     * 422 业务规则不满足：如单人施救、复检不合格等，不产生任何状态变更。
     */
    public static ApiException unprocessable(String message) {
        return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, message);
    }

    /**
     * 404 资源不存在。
     */
    public static ApiException notFound(String message) {
        return new ApiException(HttpStatus.NOT_FOUND, message);
    }
}
