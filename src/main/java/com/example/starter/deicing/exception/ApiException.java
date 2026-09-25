package com.example.starter.deicing.exception;

import org.springframework.http.HttpStatus;

/**
 * 业务异常：携带 HTTP 状态码与稳定错误码。
 */
public class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final String errorCode;

    public ApiException(HttpStatus status, String errorCode, String message) {
        super(message);
        this.status = status;
        this.errorCode = errorCode;
    }

    public HttpStatus getStatus() {
        return status;
    }

    public String getErrorCode() {
        return errorCode;
    }

    /** 同键异参（幂等指纹不一致）。 */
    public static ApiException idempotencyConflict(String message) {
        return new ApiException(HttpStatus.CONFLICT, "IDEMPOTENCY_CONFLICT", message);
    }

    /** 请求引用的申请不存在。 */
    public static ApiException notFound(String message) {
        return new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", message);
    }

    /** 当前状态不允许该操作（状态机冲突）。 */
    public static ApiException conflict(String errorCode, String message) {
        return new ApiException(HttpStatus.CONFLICT, errorCode, message);
    }

    /** 请求参数语义非法。 */
    public static ApiException badRequest(String errorCode, String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, errorCode, message);
    }
}
