package com.example.starter.cems.web.dto;

import java.math.BigDecimal;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

/**
 * 排口许可登记请求。
 *
 * @param requestId  幂等键
 * @param outfallCode 排口编码
 * @param outfallName 排口名称
 * @param dailyLimit 日均许可限值 mg/L（恰等限值判达标）
 * @param hourlyLimit 小时均值许可限值 mg/L
 */
public record RegisterRequest(
        @NotBlank String requestId,
        @NotBlank String outfallCode,
        String outfallName,
        @NotNull @PositiveOrZero BigDecimal dailyLimit,
        @NotNull @PositiveOrZero BigDecimal hourlyLimit) {
}
