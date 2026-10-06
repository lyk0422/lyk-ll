package com.example.starter.service;

import org.springframework.http.HttpStatus;

/**
 * 业务异常，携带 HTTP 状态码与错误消息。
 */
public class ApiException extends RuntimeException {

    private final HttpStatus status;

    public ApiException(HttpStatus status, String message) {
        super(message);
        this.status = status;
    }

    /**
     * 对应的 HTTP 状态码。
     */
    public HttpStatus status() {
        return status;
    }

    /**
     * 404 资源不存在。
     */
    public static ApiException notFound(String message) {
        return new ApiException(HttpStatus.NOT_FOUND, message);
    }

    /**
     * 409 状态冲突（幂等指纹不一致、管制中禁止上客等）。
     */
    public static ApiException conflict(String message) {
        return new ApiException(HttpStatus.CONFLICT, message);
    }

    /**
     * 422 业务前置条件不满足（复开条件未达成、事件类型不匹配等）。
     */
    public static ApiException unprocessable(String message) {
        return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, message);
    }
}
