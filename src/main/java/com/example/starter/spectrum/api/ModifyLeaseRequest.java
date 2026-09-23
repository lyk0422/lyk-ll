package com.example.starter.spectrum.api;

import com.example.starter.spectrum.LeaseMode;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Set;

/**
 * 修改租约请求：携带 {@code expectedVersion} 做乐观并发控制，
 * 修改成功后追加新版本（历史版本保留）。
 *
 * @param requestId       幂等键（必填）
 * @param leaseId         待修改租约标识
 * @param expectedVersion 调用方期望的当前版本；不符则拒绝
 * @param regions         新的非空区域单元集合
 * @param start           新的 UTC 生效开始时刻（含）
 * @param end             新的 UTC 生效结束时刻（不含）
 * @param freqLowMHz      新的频率下限 MHz（含）
 * @param freqHighMHz     新的频率上限 MHz（不含）
 * @param mode            新的占用模式
 * @param eirpMw          新的正数 EIRP，单位 mW
 */
public record ModifyLeaseRequest(
        @NotNull String requestId,
        @NotNull String leaseId,
        @NotNull Integer expectedVersion,
        @NotEmpty Set<String> regions,
        @NotNull Instant start,
        @NotNull Instant end,
        @NotNull BigDecimal freqLowMHz,
        @NotNull BigDecimal freqHighMHz,
        @NotNull LeaseMode mode,
        @NotNull @Positive BigDecimal eirpMw) {
}
