package com.example.starter.arbitration.service;

import org.springframework.http.HttpStatus;

import java.util.List;

/**
 * 业务 API 异常，携带 HTTP 状态码、错误码与可区分的原因列表。
 */
public class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final String code;
    private final transient List<String> reasons;

    public ApiException(HttpStatus status, String code, String reason) {
        super(reason);
        this.status = status;
        this.code = code;
        this.reasons = List.of(reason);
    }

    public ApiException(HttpStatus status, String code, List<String> reasons) {
        super(String.join("; ", reasons));
        this.status = status;
        this.code = code;
        this.reasons = List.copyOf(reasons);
    }

    public HttpStatus getStatus() {
        return status;
    }

    public String getCode() {
        return code;
    }

    public List<String> getReasons() {
        return reasons;
    }

    /** 409 冲突（异参 requestId 重放、同级抢占失败等）。 */
    public static ApiException conflict(String code, String reason) {
        return new ApiException(HttpStatus.CONFLICT, code, reason);
    }

    /** 423 锁定（急停期间普通申请被拒绝）。 */
    public static ApiException locked(String code, String reason) {
        return new ApiException(HttpStatus.LOCKED, code, reason);
    }

    /** 404 资源/租约不存在。 */
    public static ApiException notFound(String code, String reason) {
        return new ApiException(HttpStatus.NOT_FOUND, code, reason);
    }

    /** 400 请求非法（指令校验失败等）。 */
    public static ApiException badRequest(String code, List<String> reasons) {
        return new ApiException(HttpStatus.BAD_REQUEST, code, reasons);
    }
}
