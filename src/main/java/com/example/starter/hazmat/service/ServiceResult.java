package com.example.starter.hazmat.service;

import java.util.Map;

/**
 * 服务层返回：携带 HTTP 状态与响应体，支撑幂等重放首次响应状态码。
 */
public record ServiceResult(int httpStatus, Map<String, Object> body) {

    public static ServiceResult ok(Map<String, Object> body) {
        return new ServiceResult(200, body);
    }

    public static ServiceResult status(int httpStatus, Map<String, Object> body) {
        return new ServiceResult(httpStatus, body);
    }
}
