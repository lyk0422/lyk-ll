package com.example.starter.blast.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 爆破作业申请请求体。
 *
 * @param requestId        幂等键
 * @param mineCode         矿区编码
 * @param zoneCode         爆区编码
 * @param applicant        申请人
 * @param plannedTime      计划起爆时刻，UTC（例如 2026-09-26T02:00:00Z）
 * @param chargePerDelayKg 单段药量，单位 kg，必须为正数
 */
public record ApplyRequest(
        @NotBlank String requestId,
        @NotBlank String mineCode,
        @NotBlank String zoneCode,
        @NotBlank String applicant,
        @NotNull Instant plannedTime,
        @NotNull @Positive BigDecimal chargePerDelayKg) {
}
