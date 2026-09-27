package com.example.starter.runway;

import org.springframework.http.HttpStatus;

/**
 * 业务异常：携带 HTTP 状态码与稳定错误码，由全局异常处理器转为统一错误报文。
 * evidence 可选，用于 422 冲突时回传结构化冲突证据（JSON 文本）。
 */
public class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final String code;
    private final String evidence;

    public ApiException(HttpStatus status, String code, String message) {
        this(status, code, message, null);
    }

    public ApiException(HttpStatus status, String code, String message, String evidence) {
        super(message);
        this.status = status;
        this.code = code;
        this.evidence = evidence;
    }

    public HttpStatus getStatus() {
        return status;
    }

    public String getCode() {
        return code;
    }

    public String getEvidence() {
        return evidence;
    }

    public static ApiException notFound(String message) {
        return new ApiException(HttpStatus.NOT_FOUND, "EVENT_NOT_FOUND", message);
    }

    public static ApiException notFound(String code, String message) {
        return new ApiException(HttpStatus.NOT_FOUND, code, message);
    }

    public static ApiException badRequest(String code, String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, code, message);
    }

    public static ApiException conflict(String code, String message) {
        return new ApiException(HttpStatus.CONFLICT, code, message);
    }

    public static ApiException unprocessable(String code, String message) {
        return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, code, message);
    }

    public static ApiException unprocessable(String code, String message, String evidence) {
        return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, code, message, evidence);
    }
}
