package com.example.starter.core;

import org.springframework.http.HttpStatus;

/**
 * 业务异常：携带 HTTP 状态码与机器可读错误码，由统一异常处理层转换为响应体。
 */
public class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final String code;

    public ApiException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public HttpStatus status() {
        return status;
    }

    public String code() {
        return code;
    }

    /** 409 冲突，典型场景：同键异参、窑口被占用、状态不允许流转。 */
    public static ApiException conflict(String code, String message) {
        return new ApiException(HttpStatus.CONFLICT, code, message);
    }

    /** 422 业务拒绝，典型场景：审核校验失败导致整单拒绝。 */
    public static ApiException rejected(String code, String message) {
        return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, code, message);
    }

    /** 404 资源不存在。 */
    public static ApiException notFound(String code, String message) {
        return new ApiException(HttpStatus.NOT_FOUND, code, message);
    }
}
