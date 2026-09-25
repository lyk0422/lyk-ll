package com.example.starter.dto;

import java.math.BigDecimal;
import java.time.Instant;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * 爆破作业申请请求。plannedBlastTime 为 UTC 时刻；chargePerDelay 单位 kg。
 */
public record PermitRequest(
        @NotBlank String requestId,
        @NotNull Long zoneId,
        @NotBlank String applicant,
        @NotNull Instant plannedBlastTime,
        @NotNull @Positive BigDecimal chargePerDelay) {
}
