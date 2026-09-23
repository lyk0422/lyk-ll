package com.example.starter.calibration.web;

import org.springframework.http.HttpStatus;

/**
 * 标定服务 API 错误基类，携带 HTTP 状态码与错误信息。
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

    /** 标定内容或状态机校验失败：422。 */
    public static class Unprocessable extends ApiException {
        public Unprocessable(String message) {
            super(HttpStatus.UNPROCESSABLE_ENTITY, message);
        }
    }

    /** 生效范围冲突、并发冲突等：409。 */
    public static class Conflict extends ApiException {
        public Conflict(String message) {
            super(HttpStatus.CONFLICT, message);
        }
    }

    /** 资源不存在：404。 */
    public static class NotFound extends ApiException {
        public NotFound(String message) {
            super(HttpStatus.NOT_FOUND, message);
        }
    }
}
