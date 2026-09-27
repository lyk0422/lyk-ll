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

    /** 请求引用的对象不存在：404。 */
    public static ApiStatusBuilder notFound(String code) {
        return new ApiStatusBuilder(HttpStatus.NOT_FOUND, code);
    }

    /** 请求引用的数据不存在或状态非法：422。 */
    public static ApiStatusBuilder unprocessable(String code) {
        return new ApiStatusBuilder(HttpStatus.UNPROCESSABLE_ENTITY, code);
    }

    /** 分区/站点同时刻序列或任务冲突：409。 */
    public static ApiException concurrentConflict(String code, String message) {
        return new ApiException(HttpStatus.CONFLICT, code, message);
    }

    /** 乐观版本失配：409，并携带当前最新版本供调用方携带 expectedVersion 重试。 */
    public static VersionMismatchException versionMismatch(String code, String message,
                                                           Integer currentVersion) {
        return new VersionMismatchException(code, message, currentVersion);
    }

    /** 链式构造器。 */
    public record ApiStatusBuilder(HttpStatus status, String code) {
        public ApiException raise(String message) {
            return new ApiException(status, code, message);
        }
    }

    /** 版本失配异常：附带服务端当前版本，供调用方修正 expectedVersion 后重试。 */
    public static class VersionMismatchException extends ApiException {
        private final Integer currentVersion;

        public VersionMismatchException(String code, String message, Integer currentVersion) {
            super(HttpStatus.CONFLICT, code, message);
            this.currentVersion = currentVersion;
        }

        public Integer getCurrentVersion() {
            return currentVersion;
        }
    }
}
