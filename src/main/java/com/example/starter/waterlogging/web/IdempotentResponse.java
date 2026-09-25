package com.example.starter.waterlogging.web;

/**
 * 幂等接口统一响应包装：标识本次结果为首办（replay=false）还是 requestId 重放（replay=true）。
 *
 * @param replay 是否为重放的首次结果
 * @param data   业务响应体
 */
public record IdempotentResponse<T>(boolean replay, T data) {

    public static <T> IdempotentResponse<T> first(T data) {
        return new IdempotentResponse<>(false, data);
    }

    public static <T> IdempotentResponse<T> replayed(T data) {
        return new IdempotentResponse<>(true, data);
    }
}
