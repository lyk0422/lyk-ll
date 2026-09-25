package com.example.starter.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 评估请求。
 *
 * <p>{@code phase=JUDGMENT} 为失衡判定（基于当前回水温度分布）；
 * {@code phase=RE_EVAL} 为效果评估，要求距最近一次调节稳定满 4 小时（恰满 4 小时允许）。</p>
 *
 * @param requestId   幂等键，指纹覆盖本请求全部业务字段
 * @param stationCode 换热站编码
 * @param phase       评估阶段 JUDGMENT / RE_EVAL
 */
public record EvaluateRequest(
        @NotBlank String requestId,
        @NotBlank String stationCode,
        @NotBlank String phase
) {
}
