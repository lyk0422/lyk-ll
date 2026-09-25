package com.example.starter.crane.api;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 重叠区授权申请。
 *
 * @param requestId 调用方提供的幂等键
 * @param craneCode 申请塔机编号
 * @param zoneCode  申请进入的重叠区编号
 * @param startTime 作业开始时刻（UTC），半开区间左端（含）
 * @param endTime   作业结束时刻（UTC），半开区间右端（不含）
 * @param loadKg    本次吊重，单位 kg
 */
public record AuthorizationRequest(
        @NotBlank String requestId,
        @NotBlank String craneCode,
        @NotBlank String zoneCode,
        @NotNull Instant startTime,
        @NotNull Instant endTime,
        @NotNull @DecimalMin(value = "0.0", inclusive = false) BigDecimal loadKg) {
}
