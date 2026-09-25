package com.example.starter.dispatch.api;

import org.springframework.http.HttpStatus;

/**
 * 业务异常：携带 HTTP 状态码与错误码，由全局异常处理器统一转译。
 */
public class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final String code;

    public ApiException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public HttpStatus getStatus() {
        return status;
    }

    public String getCode() {
        return code;
    }

    /** 409：同键异参、状态冲突（已签收不可撤销、顺序约束等）。 */
    public static ApiException conflict(String code, String message) {
        return new ApiException(HttpStatus.CONFLICT, code, message);
    }

    /** 404：令或闸门不存在。 */
    public static ApiException notFound(String code, String message) {
        return new ApiException(HttpStatus.NOT_FOUND, code, message);
    }

    /** 422：请求内容不满足业务约束（开度越界、闸门重复等）。 */
    public static ApiException unprocessable(String code, String message) {
        return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, code, message);
    }
}
