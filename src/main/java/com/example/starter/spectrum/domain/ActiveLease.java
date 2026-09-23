package com.example.starter.spectrum.domain;

import com.example.starter.spectrum.LeaseMode;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Set;

/**
 * 冲突检查使用的租约状态视图（当前版本、且未来仍有效）。
 *
 * @param leaseId 租约标识
 * @param version 当前版本号
 * @param start   UTC 生效开始时刻（含）
 * @param end     UTC 生效结束时刻（不含）
 * @param freqLowMHz  频率下限 MHz（含）
 * @param freqHighMHz 频率上限 MHz（不含）
 * @param mode    占用模式
 * @param eirpMw  EIRP（mW）
 * @param regions 区域单元集合
 */
public record ActiveLease(
        String leaseId,
        int version,
        Instant start,
        Instant end,
        BigDecimal freqLowMHz,
        BigDecimal freqHighMHz,
        LeaseMode mode,
        BigDecimal eirpMw,
        Set<String> regions) {
}
