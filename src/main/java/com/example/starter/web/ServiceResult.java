package com.example.starter.web;

/**
 * Service 层返回值：HTTP 状态码 + 响应体（由 Controller 原样输出）。
 */
public record ServiceResult(int httpStatus, Object body) {

    public static ServiceResult ok(Object body) {
        return new ServiceResult(200, body);
    }

    public static ServiceResult created(Object body) {
        return new ServiceResult(201, body);
    }
}
