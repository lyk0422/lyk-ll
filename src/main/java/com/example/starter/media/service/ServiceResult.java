package com.example.starter.media.service;

/**
 * 写操作结果：携带业务视图与对应 HTTP 状态码（幂等回放时恢复首次状态）。
 */
public record ServiceResult<T>(T body, int statusCode) {

    public static <T> ServiceResult<T> of(T body, int statusCode) {
        return new ServiceResult<>(body, statusCode);
    }
}
