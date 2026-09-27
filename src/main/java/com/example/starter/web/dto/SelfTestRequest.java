package com.example.starter.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.time.Instant;

/**
 * 列尾装置自检更新请求；携带装置期望版本，版本不符拒绝。
 *
 * @param requestId          幂等请求键
 * @param expectedVersion    期望的装置当前版本
 * @param selfTestValidUntil 新的自检合格有效截止时刻（UTC）
 */
public record SelfTestRequest(
        @NotBlank String requestId,
        @NotNull @Positive Integer expectedVersion,
        @NotNull Instant selfTestValidUntil) {
}
