package com.example.starter.playout.service;

/**
 * 服务层返回包装；携带 HTTP 状态码与是否为幂等重放，便于控制器原样回放冻结结果。
 *
 * @param body      响应体（成功 DTO 或错误体）
 * @param status    首次结果冻结的 HTTP 状态码
 * @param replay    true 表示本次为同键同参重放
 * @param <T>       响应体类型
 */
public record ServiceResult<T>(T body, int status, boolean replay) {

    public static <T> ServiceResult<T> first(T body, int status) {
        return new ServiceResult<>(body, status, false);
    }

    public static <T> ServiceResult<T> replay(T body, int status) {
        return new ServiceResult<>(body, status, true);
    }
}
