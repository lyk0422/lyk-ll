package com.example.starter.support;

import org.springframework.http.HttpStatus;

/**
 * 业务规则异常：携带 HTTP 状态码与稳定的错误码，由全局异常处理器统一转换为响应体。
 */
public class BusinessException extends RuntimeException {

    private final HttpStatus status;
    private final String code;

    public BusinessException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public static BusinessException badRequest(String code, String message) {
        return new BusinessException(HttpStatus.BAD_REQUEST, code, message);
    }

    public static BusinessException unprocessable(String code, String message) {
        return new BusinessException(HttpStatus.UNPROCESSABLE_ENTITY, code, message);
    }

    public static BusinessException conflict(String code, String message) {
        return new BusinessException(HttpStatus.CONFLICT, code, message);
    }

    public static BusinessException notFound(String code, String message) {
        return new BusinessException(HttpStatus.NOT_FOUND, code, message);
    }

    public HttpStatus getStatus() {
        return status;
    }

    public String getCode() {
        return code;
    }
}
