package com.example.starter.ride;

import org.springframework.http.HttpStatus;

/**
 * 业务异常：携带 HTTP 状态码与可读错误信息，由全局异常处理器统一输出。
 */
public class ApiException extends RuntimeException {

    private final HttpStatus status;

    public ApiException(HttpStatus status, String message) {
        super(message);
        this.status = status;
    }

    public HttpStatus status() {
        return status;
    }

    /** 状态机冲突（如未日检禁开放、当前状态不允许该操作）。 */
    public static ApiException conflict(String message) {
        return new ApiException(HttpStatus.CONFLICT, message);
    }

    /** 业务规则不满足（如同日复开超出2次当日禁开）。 */
    public static ApiException unprocessable(String message) {
        return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, message);
    }

    /** 资源不存在。 */
    public static ApiException notFound(String message) {
        return new ApiException(HttpStatus.NOT_FOUND, message);
    }

    /** 参数非法（如有效截止不晚于检验合格时刻）。 */
    public static ApiException badRequest(String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, message);
    }
}
