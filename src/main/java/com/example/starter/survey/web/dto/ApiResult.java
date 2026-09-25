package com.example.starter.survey.web.dto;

/**
 * 幂等信封：所有写接口统一返回。
 *
 * @param data      业务结果（同键同参重放时为首次结果）
 * @param requestId 幂等键
 * @param replayed  true 表示命中历史指纹、直接重放首次结果
 * @param <T>       业务结果类型
 */
public record ApiResult<T>(T data, String requestId, boolean replayed) {

    public static <T> ApiResult<T> first(T data, String requestId) {
        return new ApiResult<>(data, requestId, false);
    }

    public static <T> ApiResult<T> replay(T data, String requestId) {
        return new ApiResult<>(data, requestId, true);
    }
}
