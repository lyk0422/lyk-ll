package com.example.starter.cems.web.dto;

/**
 * 幂等写操作统一响应信封。
 *
 * @param replayed 是否为同键重放（true 时 result 为首次结果的原样回放）
 * @param result 业务结果
 * @param <T> 业务结果类型
 */
public record MutationResponse<T>(boolean replayed, T result) {

    public static <T> MutationResponse<T> first(T result) {
        return new MutationResponse<>(false, result);
    }

    public static <T> MutationResponse<T> replay(T result) {
        return new MutationResponse<>(true, result);
    }
}
