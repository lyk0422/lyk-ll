package com.example.starter.waterlogging.api;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

/**
 * 泵站单机启停指令。
 *
 * @param requestId    幂等键
 * @param stationCode  目标泵站
 * @param action       START 启机 / STOP 停机
 * @param commandTime  指令时刻；同站同时刻唯一受理
 * @param gateOpening  闸门开度（百分比）：START 必须大于 0，与启机在同一事务内调整到位
 */
public record PumpCommandRequest(
        @NotBlank String requestId,
        @NotBlank String stationCode,
        @NotBlank @Pattern(regexp = "START|STOP") String action,
        @NotNull LocalDateTime commandTime,
        @NotNull @DecimalMin("0.00") @DecimalMax("100.00") BigDecimal gateOpening) {
}
