package com.example.starter.web.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 储罐登记请求：把门站与一个加臭剂储罐绑定并登记容量、安全储备与期初余额。
 *
 * @param stationCode      门站编码
 * @param requestId        幂等键
 * @param tankCode         储罐编码（唯一）
 * @param capacityKg       容量 kg，三位小数
 * @param safetyReserveKg  安全储备 kg，三位小数，0 ≤ 安全储备 &lt; 容量
 * @param initialBalanceKg 期初余额 kg，三位小数，0 ≤ 余额 ≤ 容量
 * @param registeredAt     登记时刻（业务时间，Asia/Shanghai）
 */
public record TankRegisterRequest(
        @NotBlank String stationCode,
        @NotBlank String requestId,
        @NotBlank String tankCode,
        @NotNull @DecimalMin(value = "0.001", message = "容量必须为正") BigDecimal capacityKg,
        @NotNull @DecimalMin("0") BigDecimal safetyReserveKg,
        @NotNull @DecimalMin("0") BigDecimal initialBalanceKg,
        @NotNull LocalDateTime registeredAt) {
}
