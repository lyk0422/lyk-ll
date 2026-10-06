package com.example.starter.exception;

import org.springframework.http.HttpStatus;

/**
 * 业务异常：携带 HTTP 状态码与稳定错误码，由全局异常处理器统一转换为响应。
 */
public class BizException extends RuntimeException {

    private final HttpStatus status;
    private final String code;

    public BizException(HttpStatus status, String code, String message) {
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

    /** 资源不存在，404。 */
    public static BizException notFound(String message) {
        return new BizException(HttpStatus.NOT_FOUND, "NOT_FOUND", message);
    }

    /** 请求参数非法，400。 */
    public static BizException badRequest(String code, String message) {
        return new BizException(HttpStatus.BAD_REQUEST, code, message);
    }

    /** 业务状态冲突，409。 */
    public static BizException conflict(String code, String message) {
        return new BizException(HttpStatus.CONFLICT, code, message);
    }
}
