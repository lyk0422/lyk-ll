package com.example.starter.api.dto;

import java.math.BigDecimal;
import java.time.Instant;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * 采集包：会话内从 1 开始的连续序号、UTC 测量时刻、点号与有限正力矩。
 *
 * @param seq            会话内连续序号，从 1 开始
 * @param measuredAt     UTC 测量时刻（设备产生）
 * @param measuredTorque 实测力矩(N·m)，必须为有限正数
 */
public record CollectionPacketRequest(
        @NotNull @Positive Integer seq,
        @NotBlank String pointCode,
        @NotNull @Positive BigDecimal measuredTorque,
        @NotNull Instant measuredAt) {
}
