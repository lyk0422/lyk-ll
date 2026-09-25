package com.example.starter.service;

import java.util.Map;

/**
 * 服务层返回：携带 HTTP 状态码与响应体；幂等重放时恢复首次状态码。
 */
public record ServiceResult(int status, Map<String, Object> body, boolean replayed) {

    public static ServiceResult created(Map<String, Object> body) {
        return new ServiceResult(201, body, false);
    }

    public static ServiceResult ok(Map<String, Object> body) {
        return new ServiceResult(200, body, false);
    }

    public ServiceResult asReplay() {
        return new ServiceResult(status, body, true);
    }
}
