package com.example.starter.feed.web;

import org.springframework.http.HttpStatus;

/**
 * API 业务异常基类；携带对应 HTTP 状态码。
 */
public class ApiException extends RuntimeException {

    private final HttpStatus status;

    public ApiException(HttpStatus status, String message) {
        super(message);
        this.status = status;
    }

    public HttpStatus getStatus() {
        return status;
    }

    /** 资源不存在（404）。 */
    public static ApiException notFound(String message) {
        return new ApiException(HttpStatus.NOT_FOUND, message);
    }

    /** 状态冲突（409）：同键异参、并发版本冲突、重复投产等。 */
    public static ApiException conflict(String message) {
        return new ApiException(HttpStatus.CONFLICT, message);
    }

    /** 不可处理实体（422）：业务前置条件不满足（未批准即投产、休药期内出栏等）。 */
    public static ApiException unprocessable(String message) {
        return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, message);
    }
}
