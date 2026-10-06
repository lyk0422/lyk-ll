package com.example.starter.error;

import org.springframework.http.HttpStatus;

/**
 * 业务异常：携带 HTTP 状态与业务错误码，由全局异常处理器转换为响应。
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

    /** 404：资源不存在。 */
    public static BusinessException notFound(String message) {
        return new BusinessException(HttpStatus.NOT_FOUND, "NOT_FOUND", message);
    }

    /** 409：幂等键冲突（同键异参）或资源状态冲突。 */
    public static BusinessException conflict(String code, String message) {
        return new BusinessException(HttpStatus.CONFLICT, code, message);
    }

    /** 422：业务规则校验失败（工时、窗口冲突、状态机非法流转等）。 */
    public static BusinessException unprocessable(String code, String message) {
        return new BusinessException(HttpStatus.UNPROCESSABLE_ENTITY, code, message);
    }
}
