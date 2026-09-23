package com.example.starter.costmap.web;

import java.time.Instant;

/**
 * 稳定错误响应体。
 *
 * @param error     稳定错误码
 * @param message   人类可读信息（不含堆栈）
 * @param requestId 触发错误的幂等请求 ID（无则为 null）
 * @param timestamp 错误发生时刻（epoch 毫秒）
 */
public record ErrorResponse(String error, String message, String requestId, long timestamp) {

    public static ErrorResponse of(ErrorCode code, String message, String requestId) {
        return new ErrorResponse(code.code(), message, requestId, Instant.now().toEpochMilli());
    }
}
