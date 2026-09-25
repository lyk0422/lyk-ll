package com.example.starter.web;

import org.springframework.http.HttpStatus;

/**
 * 业务异常：携带 HTTP 状态码与机器可读错误码。
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

    /** 资源不存在（换热站/单元/任务）。 */
    public static ApiException notFound(String message) {
        return new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", message);
    }

    /** 业务规则不满足（稳定时间不足、终态任务、数据不足等）。 */
    public static ApiException unprocessable(String code, String message) {
        return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, code, message);
    }

    /** 冲突（幂等指纹不一致、换热站已有执行中任务等）。 */
    public static ApiException conflict(String code, String message) {
        return new ApiException(HttpStatus.CONFLICT, code, message);
    }
}
