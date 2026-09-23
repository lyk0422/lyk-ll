package com.example.starter.spectrum.api;

import com.example.starter.spectrum.LeaseMode;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Set;

/**
 * 创建租约请求。
 *
 * @param requestId 幂等键（必填）
 * @param regions   非空区域单元集合
 * @param start     UTC 生效开始时刻（含）
 * @param end       UTC 生效结束时刻（不含）
 * @param freqLowMHz  频率下限 MHz（含）
 * @param freqHighMHz 频率上限 MHz（不含）
 * @param mode      EXCLUSIVE / SHARED
 * @param eirpMw    正数 EIRP，单位 mW
 */
public record CreateLeaseRequest(
        @NotNull String requestId,
        @NotEmpty Set<String> regions,
        @NotNull Instant start,
        @NotNull Instant end,
        @NotNull BigDecimal freqLowMHz,
        @NotNull BigDecimal freqHighMHz,
        @NotNull LeaseMode mode,
        @NotNull @Positive BigDecimal eirpMw) {
}
