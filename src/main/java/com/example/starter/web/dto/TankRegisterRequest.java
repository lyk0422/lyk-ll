package com.example.starter.web.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 储罐登记请求。
 *
 * @param stationCode     门站编码
 * @param requestId       幂等键
 * @param tankCode        合成储罐编码，全局唯一
 * @param capacityKg      容量 kg，三位小数
 * @param safetyReserveKg 安全储备 kg，须满足 0≤安全储备&lt;容量
 * @param initialBalanceKg 初始余额 kg，0≤余额≤容量
 * @param registeredAt    登记时刻（业务时间）
 */
public record TankRegisterRequest(
        @NotBlank String stationCode,
        @NotBlank String requestId,
        @NotBlank String tankCode,
        @NotNull @DecimalMin(value = "0", inclusive = false) BigDecimal capacityKg,
        @NotNull @DecimalMin("0") BigDecimal safetyReserveKg,
        @NotNull @DecimalMin("0") BigDecimal initialBalanceKg,
        @NotNull LocalDateTime registeredAt) {
}
