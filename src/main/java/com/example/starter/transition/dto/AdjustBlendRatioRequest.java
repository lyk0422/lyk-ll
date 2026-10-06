package com.example.starter.transition.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

/**
 * 执行中掺配比例调整请求：生成新的比例版本；同一订单同时刻只允许一次执行中调整。
 *
 * @param requestId         幂等键
 * @param targetBlendRatioPct 新目标回抄浆掺配比例，单位 %，须在合成允许区间内（恰达边界合法）
 */
public record AdjustBlendRatioRequest(
        @NotBlank String requestId,
        @NotNull @DecimalMin(value = "0.0", inclusive = false) BigDecimal targetBlendRatioPct) {
}
