package com.example.starter.roaming.service;

/**
 * 服务层返回值：携带首次发生时的 HTTP 状态与响应体，供幂等重放原样返回。
 */
public record ServiceResult(int status, Object body) {

    public static ServiceResult ok(Object body) {
        return new ServiceResult(200, body);
    }
}
