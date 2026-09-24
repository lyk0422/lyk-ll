package com.example.starter.carbon.dto;

import java.time.Instant;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * 核定企业年度排放量并设置履约截止时刻。
 *
 * @param emissions  核定排放量（吨CO2e）
 * @param deadlineAt 履约截止时刻（UTC）；恰在截止时刻的申请仍受理
 */
public record SetEmissionsRequest(
        @Min(0) long emissions,
        @NotNull Instant deadlineAt) {
}
