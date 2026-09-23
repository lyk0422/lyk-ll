package com.example.starter.dr;

import org.springframework.http.HttpStatus;

/**
 * 需求响应业务异常：携带 HTTP 状态码与机器可读错误码。
 */
public class DrApiException extends RuntimeException {

    private final HttpStatus status;
    private final String code;

    public DrApiException(HttpStatus status, String code, String message) {
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

    public static DrApiException notFound(String message) {
        return new DrApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", message);
    }

    public static DrApiException badRequest(String code, String message) {
        return new DrApiException(HttpStatus.BAD_REQUEST, code, message);
    }

    public static DrApiException conflict(String code, String message) {
        return new DrApiException(HttpStatus.CONFLICT, code, message);
    }

    public static DrApiException unprocessable(String code, String message) {
        return new DrApiException(HttpStatus.UNPROCESSABLE_ENTITY, code, message);
    }
}
