package com.example.starter.cems.web.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

/**
 * 复测解除请求：复测小时均值合格（小于等于小时限值为达标，恰等判达标）才可解除限排。
 *
 * @param requestId 幂等键
 * @param outfallCode 排口编码
 * @param periodStart 复测小时时段起始（整点）
 * @param avgValue 复测小时均值 mg/L
 */
public record ReleaseRequest(
        @NotBlank String requestId,
        @NotBlank String outfallCode,
        @NotNull LocalDateTime periodStart,
        @NotNull @PositiveOrZero BigDecimal avgValue) {
}
