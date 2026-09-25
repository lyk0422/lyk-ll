package com.example.starter.exception;

/**
 * 业务异常，携带 HTTP 状态码与错误码。
 */
public class ApiException extends RuntimeException {

    private final int httpStatus;
    private final String code;

    public ApiException(int httpStatus, String code, String message) {
        super(message);
        this.httpStatus = httpStatus;
        this.code = code;
    }

    public int getHttpStatus() {
        return httpStatus;
    }

    public String getCode() {
        return code;
    }

    public static ApiException notFound(String message) {
        return new ApiException(404, "NOT_FOUND", message);
    }

    public static ApiException conflict(String code, String message) {
        return new ApiException(409, code, message);
    }

    public static ApiException unprocessable(String code, String message) {
        return new ApiException(422, code, message);
    }

    public static ApiException badRequest(String code, String message) {
        return new ApiException(400, code, message);
    }
}
