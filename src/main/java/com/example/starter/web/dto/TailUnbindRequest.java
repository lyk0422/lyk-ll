package com.example.starter.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.time.Instant;

/**
 * 发车前提前解绑请求；仅允许发车确认前，同时撤销一致性确认。
 *
 * @param requestId       幂等请求键
 * @param expectedVersion 装置期望版本（乐观锁），成功解绑后版本加 1
 * @param unboundAt       解绑时刻（UTC 精确时刻）
 */
public record TailUnbindRequest(
        @NotBlank String requestId,
        @NotNull @Positive Integer expectedVersion,
        @NotNull Instant unboundAt) {
}
