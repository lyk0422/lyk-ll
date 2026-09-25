package com.example.starter.pump.error;

import org.springframework.http.HttpStatus;

/**
 * 业务异常：携带 HTTP 状态码与稳定错误码。
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

    /** 同键异参：409 冲突。 */
    public static ApiException conflict(String message) {
        return new ApiException(HttpStatus.CONFLICT, "IDEMPOTENT_CONFLICT", message);
    }

    /** 请求引用的数据不存在或状态非法：422。 */
    public static ApiStatusBuilder unprocessable(String code) {
        return new ApiStatusBuilder(HttpStatus.UNPROCESSABLE_ENTITY, code);
    }

    /** 分区/站点同时刻序列或任务冲突：409。 */
    public static ApiException concurrentConflict(String code, String message) {
        return new ApiException(HttpStatus.CONFLICT, code, message);
    }

    /** 链式构造器。 */
    public record ApiStatusBuilder(HttpStatus status, String code) {
        public ApiException raise(String message) {
            return new ApiException(status, code, message);
        }
    }
}
