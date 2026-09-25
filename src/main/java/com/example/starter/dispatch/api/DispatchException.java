package com.example.starter.dispatch.api;

import org.springframework.http.HttpStatus;

/**
 * 调度业务异常：携带 HTTP 状态与稳定错误码。
 */
public class DispatchException extends RuntimeException {

    private final HttpStatus status;
    private final String code;

    public DispatchException(HttpStatus status, String code, String message) {
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

    public static DispatchException conflict(String code, String message) {
        return new DispatchException(HttpStatus.CONFLICT, code, message);
    }

    public static DispatchException notFound(String message) {
        return new DispatchException(HttpStatus.NOT_FOUND, "ORDER_NOT_FOUND", message);
    }

    public static DispatchException badRequest(String code, String message) {
        return new DispatchException(HttpStatus.BAD_REQUEST, code, message);
    }
}
