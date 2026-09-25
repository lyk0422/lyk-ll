package com.example.starter.metro;

import org.springframework.http.HttpStatus;

/**
 * 业务异常：携带 HTTP 状态码与错误码，由全局异常处理器转换为统一错误响应。
 */
public class MetroException extends RuntimeException {

    private final HttpStatus status;
    private final String code;

    public MetroException(HttpStatus status, String code, String message) {
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

    public static MetroException conflict(String code, String message) {
        return new MetroException(HttpStatus.CONFLICT, code, message);
    }

    public static MetroException notFound(String code, String message) {
        return new MetroException(HttpStatus.NOT_FOUND, code, message);
    }

    public static MetroException badRequest(String code, String message) {
        return new MetroException(HttpStatus.BAD_REQUEST, code, message);
    }
}
