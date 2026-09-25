package com.example.starter.common;

import org.springframework.http.HttpStatus;

/**
 * 业务异常：携带 HTTP 状态与业务错误码，由全局异常处理器转为统一错误响应。
 */
public class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final String code;

    public ApiException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public HttpStatus getStatus() {
        return status;
    }

    public String getCode() {
        return code;
    }

    /**
     * 幂等冲突：同 requestId 但业务字段不一致，HTTP 409。
     */
    public static ApiException conflict(String message) {
        return new ApiException(HttpStatus.CONFLICT, "IDEMPOTENCY_CONFLICT", message);
    }

    /**
     * 资源不存在，HTTP 404。
     */
    public static ApiException notFound(String message) {
        return new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", message);
    }

    /**
     * 业务规则不满足，HTTP 422。
     */
    public static ApiException unprocessable(String code, String message) {
        return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, code, message);
    }
}
