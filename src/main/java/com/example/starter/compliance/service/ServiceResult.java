package com.example.starter.compliance.service;

/**
 * 服务调用结果包装：body 为响应体，replayed 标记是否命中 requestId 幂等重放，status 为首次响应状态码。
 */
public record ServiceResult<T>(T body, boolean replayed, int status) {

    public static <T> ServiceResult<T> first(T body) {
        return new ServiceResult<>(body, false, 200);
    }

    public static <T> ServiceResult<T> replay(T body, int status) {
        return new ServiceResult<>(body, true, status);
    }
}
