package com.example.starter.wb.exception;

/**
 * 请求语义不合法（HTTP 422）：重量/重心超限、重复岗位签字、缺双岗等。
 */
public class UnprocessableException extends RuntimeException {
    public UnprocessableException(String message) {
        super(message);
    }
}
