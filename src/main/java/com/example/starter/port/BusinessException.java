package com.example.starter.port;

import org.springframework.http.HttpStatus;

/**
 * 业务异常：携带 HTTP 状态与稳定错误码，由全局异常处理器转换为响应体。
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
     * 404：资源不存在。
     */
    public static BusinessException notFound(String code, String message) {
        return new BusinessException(HttpStatus.NOT_FOUND, code, message);
    }

    /**
     * 409：幂等冲突或状态冲突。
     */
    public static BusinessException conflict(String code, String message) {
        return new BusinessException(HttpStatus.CONFLICT, code, message);
    }

    /**
     * 422：业务规则不满足（资格、授权码、生效窗口等）。
     */
    public static BusinessException unprocessable(String code, String message) {
        return new BusinessException(HttpStatus.UNPROCESSABLE_ENTITY, code, message);
    }
}
