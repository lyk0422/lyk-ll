package com.example.starter.error;

import org.springframework.http.HttpStatus;

/**
 * 业务异常，携带 HTTP 状态码与稳定错误码。
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

    /**
     * 幂等键冲突：同 requestId 但业务字段不一致。
     */
    public static ApiException conflict(String message) {
        return new ApiException(HttpStatus.CONFLICT, "IDEMPOTENCY_CONFLICT", message);
    }

    /**
     * 业务规则不满足（未审批备案、未冻结解冻、回传未齐全等）。
     */
    public static ApiException unprocessable(String code, String message) {
        return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, code, message);
    }

    /**
     * 资源不存在。
     */
    public static ApiException notFound(String message) {
        return new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", message);
    }
}
