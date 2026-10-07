package com.example.starter.web.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 储罐登记请求：登记门站、容量、安全储备与三位小数千克余额；
 * 可为启用跨罐调拨的合成罐登记不可变品种号。
 *
 * @param stationCode      门站编码
 * @param tankCode         储罐唯一编码
 * @param requestId        幂等键
 * @param capacityKg       罐容量 kg，须为正，至多三位小数
 * @param safetyReserveKg  安全储备 kg，0≤安全储备<容量
 * @param initialBalanceKg 期初余额 kg，0≤余额≤容量
 * @param registeredAt     登记时刻（业务时间）
 * @param varietyNo        加臭剂品种号，登记后不可变；为空表示不启用跨罐调拨
 */
public record TankRegisterRequest(
        @NotBlank String stationCode,
        @NotBlank String tankCode,
        @NotBlank String requestId,
        @NotNull @DecimalMin("0.001") BigDecimal capacityKg,
        @NotNull @DecimalMin("0.0") BigDecimal safetyReserveKg,
        @NotNull @DecimalMin("0.0") BigDecimal initialBalanceKg,
        @NotNull LocalDateTime registeredAt,
        String varietyNo) {

    /**
     * 兼容旧调用：不登记品种号（不启用跨罐调拨）。
     */
    public TankRegisterRequest(String stationCode, String tankCode, String requestId,
                               BigDecimal capacityKg, BigDecimal safetyReserveKg,
                               BigDecimal initialBalanceKg, LocalDateTime registeredAt) {
        this(stationCode, tankCode, requestId, capacityKg, safetyReserveKg, initialBalanceKg,
                registeredAt, null);
    }
}
