package com.example.starter.rolling.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 轧辊上机申请请求。
 *
 * @param requestId     幂等键，指纹覆盖该键与全部业务字段
 * @param rollNo        轧辊号
 * @param cycleLimitKm  轧辊周期上限（公里）；已存在轧辊沿用台账原上限
 * @param mountedAt     上机时刻（业务时间），其后的前 5 卷为首用烫辊位
 */
public record RollMountRequest(
        @NotBlank String requestId,
        @NotBlank String rollNo,
        @NotNull @Positive BigDecimal cycleLimitKm,
        @NotNull LocalDateTime mountedAt) {
}
