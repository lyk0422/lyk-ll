package com.example.starter.blast.api;

/**
 * 携带 HTTP 状态的服务结果，用于幂等重放时还原首次响应状态。
 *
 * @param status  HTTP 状态码
 * @param body    响应体
 * @param replayed 是否为幂等重放
 */
public record ServiceResult<T>(int status, T body, boolean replayed) {

    public static <T> ServiceResult<T> of(int status, T body) {
        return new ServiceResult<>(status, body, false);
    }

    public static <T> ServiceResult<T> replay(int status, T body) {
        return new ServiceResult<>(status, body, true);
    }
}
