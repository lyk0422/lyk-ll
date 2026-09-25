package com.example.starter.api;

import org.springframework.http.HttpStatus;

/**
 * 业务异常：携带 HTTP 状态码与稳定错误码，由全局异常处理器转换为统一错误报文。
 */
public class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final String code;

    public ApiException(HttpStatus status, String code, String message) {
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

    /** 同 requestId 携带不同业务参数重放。 */
    public static ApiException idempotencyConflict(String requestId) {
        return new ApiException(HttpStatus.CONFLICT, "IDEMPOTENCY_CONFLICT",
                "requestId " + requestId + " 已受理，但本次请求参数与首次不一致");
    }

    /** 业务状态冲突（如重复日志、存在进行中航次、重复进港报告）。 */
    public static ApiException conflict(String message) {
        return new ApiException(HttpStatus.CONFLICT, "CONFLICT", message);
    }

    /** 资源不存在。 */
    public static ApiException notFound(String message) {
        return new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", message);
    }

    /** 业务规则不满足（如配额超限、状态机非法流转、余额冻结中）。 */
    public static ApiException unprocessable(String code, String message) {
        return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, code, message);
    }

    /** 请求参数不满足业务约束（如缺差异说明）。 */
    public static ApiException badRequest(String code, String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, code, message);
    }
}
