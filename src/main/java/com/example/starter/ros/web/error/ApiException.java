package com.example.starter.ros.web.error;

import org.springframework.http.HttpStatus;

/**
 * 业务 API 异常,携带 HTTP 状态与可区分的机器可读错误码。
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
}
