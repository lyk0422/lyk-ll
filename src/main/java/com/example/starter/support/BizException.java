package com.example.starter.support;

import org.springframework.http.HttpStatus;

/**
 * 业务异常：携带稳定错误码与 HTTP 状态，由全局异常处理器转换为响应体。
 */
public class BizException extends RuntimeException {

    private final String code;
    private final HttpStatus status;

    public BizException(String code, HttpStatus status, String message) {
        super(message);
        this.code = code;
        this.status = status;
    }

    public String getCode() {
        return code;
    }

    public HttpStatus getStatus() {
        return status;
    }

    public static BizException badRequest(String code, String message) {
        return new BizException(code, HttpStatus.BAD_REQUEST, message);
    }

    public static BizException notFound(String code, String message) {
        return new BizException(code, HttpStatus.NOT_FOUND, message);
    }

    public static BizException conflict(String code, String message) {
        return new BizException(code, HttpStatus.CONFLICT, message);
    }
}
