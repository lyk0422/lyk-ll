package com.example.starter.web.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 加臭剂储罐登记请求：一站一罐，登记后版本从 1 开始。
 *
 * @param stationCode      门站编码
 * @param requestId        幂等键
 * @param capacityKg       储罐容量 kg（三位小数，须大于安全储备）
 * @param safetyReserveKg  安全储备 kg（0 ≤ 安全储备 < 容量）
 * @param initialBalanceKg 初始余额 kg（0 ≤ 余额 ≤ 容量）
 * @param registeredAt     登记精确时刻（业务时间，Asia/Shanghai）
 */
public record TankRegisterRequest(
        @NotBlank String stationCode,
        @NotBlank String requestId,
        @NotNull @DecimalMin(value = "0", inclusive = false) BigDecimal capacityKg,
        @NotNull @DecimalMin("0") BigDecimal safetyReserveKg,
        @NotNull @DecimalMin("0") BigDecimal initialBalanceKg,
        @NotNull LocalDateTime registeredAt) {
}
