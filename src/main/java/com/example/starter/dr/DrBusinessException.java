package com.example.starter.dr;

import org.springframework.http.HttpStatus;

/**
 * 需求响应业务异常基类，携带对应 HTTP 状态码。
 */
public class DrBusinessException extends RuntimeException {

    private final HttpStatus status;

    public DrBusinessException(HttpStatus status, String message) {
        super(message);
        this.status = status;
    }

    public HttpStatus getStatus() {
        return status;
    }
}
