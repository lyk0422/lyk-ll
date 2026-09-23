package com.example.starter.ct;

import org.springframework.http.HttpStatus;

/**
 * 业务异常：携带稳定错误码与 HTTP 状态，由全局异常处理器转换为统一错误响应。
 */
public class CtException extends RuntimeException {

    private final HttpStatus status;
    private final String code;

    public CtException(HttpStatus status, String code, String message) {
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

    public static CtException badRequest(String code, String message) {
        return new CtException(HttpStatus.BAD_REQUEST, code, message);
    }

    public static CtException conflict(String code, String message) {
        return new CtException(HttpStatus.CONFLICT, code, message);
    }

    public static CtException unauthorized(String code, String message) {
        return new CtException(HttpStatus.UNAUTHORIZED, code, message);
    }
}
