package com.example.starter.dto;

import java.time.Instant;

/**
 * 单个包装子批与通关单的绑定明细视图。
 *
 * @param batchNo          子批评号
 * @param declaredQuantity 申报预占支数（单位：支）
 * @param status           绑定状态 RESERVED/WRITTEN_OFF/RELEASED
 * @param version          绑定版本号（绑定为 1，撤回释放后加 1）
 * @param reservedAt       预占时刻
 * @param writtenOffAt     核销时刻，空值表示未核销
 * @param releasedAt       释放时刻，空值表示未释放
 */
public record BindingView(
        String batchNo,
        int declaredQuantity,
        String status,
        int version,
        Instant reservedAt,
        Instant writtenOffAt,
        Instant releasedAt
) {
}
