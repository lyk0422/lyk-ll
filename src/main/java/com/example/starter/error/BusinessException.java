package com.example.starter.error;

import org.springframework.http.HttpStatus;

/**
 * 业务异常：携带 HTTP 状态码与可读错误码，由全局异常处理器统一转换为响应。
 */
public class BusinessException extends RuntimeException {

    private final HttpStatus status;
    private final String code;

    public BusinessException(HttpStatus status, String code, String message) {
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

    /**
     * 资源不存在，404。
     */
    public static BusinessException notFound(String message) {
        return new BusinessException(HttpStatus.NOT_FOUND, "NOT_FOUND", message);
    }

    /**
     * 状态或幂等冲突，409。
     */
    public static BusinessException conflict(String code, String message) {
        return new BusinessException(HttpStatus.CONFLICT, code, message);
    }

    /**
     * 业务规则不满足，422。
     */
    public static BusinessException unprocessable(String code, String message) {
        return new BusinessException(HttpStatus.UNPROCESSABLE_ENTITY, code, message);
    }
}
