package com.example.starter.transition.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

/**
 * 执行中掺配比例调整请求：携带比例变更须重新走方式判定，
 * 判定结论与比例版本在同一事务内落库。
 *
 * @param requestId          幂等键
 * @param targetBlendRatioPct 新目标掺配比例，单位 %，须在合成允许区间内（恰在边界合法）
 */
public record BlendAdjustRequest(
        @NotBlank String requestId,
        @NotNull @DecimalMin(value = "0.0", inclusive = false) BigDecimal targetBlendRatioPct) {
}
