package com.example.starter.waste;

import org.springframework.http.HttpStatus;

/**
 * 台账业务异常，携带 HTTP 状态与稳定错误码，由全局异常处理器转换为响应体。
 */
public class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final String code;

    public ApiException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public HttpStatus status() {
        return status;
    }

    public String code() {
        return code;
    }
}
