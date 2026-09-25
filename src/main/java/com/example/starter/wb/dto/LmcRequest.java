package com.example.starter.wb.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

/**
 * 最后一分钟修正（LMC）请求。
 * 旅客增量作用于旅客站位，货物增量作用于货运站位；燃油增量同步触发重心重算。
 *
 * @param passengerDelta  旅客增量，人（可负）
 * @param paxStationCode  旅客增量所在旅客站位
 * @param paxUnitWeightKg 单客重量，kg/人（保证增量可复算）
 * @param weightDeltaKg   货物/行李/邮件增量，kg（可负）
 * @param weightCategory  增量类别：BAGGAGE/CARGO/MAIL
 * @param weightStationCode 增量所在货运站位
 * @param fuelDeltaKg     加油增量，kg（可负）；非 0 时同事务重算重心
 */
public record LmcRequest(
        @NotBlank String requestId,
        int passengerDelta,
        @NotBlank String paxStationCode,
        @NotNull BigDecimal paxUnitWeightKg,
        @NotNull BigDecimal weightDeltaKg,
        @NotBlank String weightCategory,
        @NotBlank String weightStationCode,
        @NotNull BigDecimal fuelDeltaKg,
        @NotBlank String operator
) {
}
