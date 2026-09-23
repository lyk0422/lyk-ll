package com.example.starter.ros.error;

/** 业务异常，映射为 HTTP 409（或指定 status）。 */
public class ApiConflictException extends RuntimeException {
    public ApiConflictException(String message) {
        super(message);
    }
}
