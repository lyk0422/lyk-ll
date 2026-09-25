package com.example.starter.waterlogging.api;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

/**
 * 气象预警启动预降请求。
 *
 * @param requestId      幂等键
 * @param warningLevel   预警级别编码 BLUE/YELLOW/ORANGE/RED
 * @param basinZone      流域分区
 * @param riverName      河道名称
 * @param targetLevel    预降目标水位（米）
 * @param warningTime    预警发布时刻，亦为首轮启机时刻
 * @param gateOpening    联动启泵闸门开度（百分比 0-100，不含 0）
 */
public record WarningStartRequest(
        @NotBlank String requestId,
        @NotBlank @Pattern(regexp = "BLUE|YELLOW|ORANGE|RED") String warningLevel,
        @NotBlank String basinZone,
        @NotBlank String riverName,
        @NotNull @DecimalMin("0.00") BigDecimal targetLevel,
        @NotNull LocalDateTime warningTime,
        @NotNull @DecimalMin(value = "0.01", message = "启泵闸门开度必须大于 0") @DecimalMax("100.00")
        BigDecimal gateOpening) {
}
