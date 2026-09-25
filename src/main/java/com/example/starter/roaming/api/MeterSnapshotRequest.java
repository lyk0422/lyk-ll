package com.example.starter.roaming.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * 计量快照上传请求；快照按“会话号＋抄表序号”去重，乱序按抄表序号归位。
 *
 * @param requestId   业务幂等键
 * @param readingSeq  抄表序号（非负，递增）
 * @param energyKwh   该序号电表累计读数（kWh，非负）
 * @param recordedAt  桩端计量时刻（UTC）
 */
public record MeterSnapshotRequest(
        @NotBlank String requestId,
        @NotNull Integer readingSeq,
        @NotNull @PositiveOrZero BigDecimal energyKwh,
        @NotNull Instant recordedAt) {
}
