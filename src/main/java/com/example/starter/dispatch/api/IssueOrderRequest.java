package com.example.starter.dispatch.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * 调度令下达请求。闸门列表与目标开度序列一一对应，执行时限为 UTC 时刻。
 *
 * @param requestId     幂等键
 * @param gates         闸门编号序列，按序依次执行
 * @param targetOpenings 各闸门目标开度（百分比，0-100）
 * @param deadline      执行时限（UTC），超时未收齐回执将自动升级
 */
public record IssueOrderRequest(
        @NotBlank String requestId,
        @NotEmpty @Size(max = 50) List<@NotBlank String> gates,
        @NotEmpty List<@NotNull BigDecimal> targetOpenings,
        @NotNull Instant deadline) {
}
