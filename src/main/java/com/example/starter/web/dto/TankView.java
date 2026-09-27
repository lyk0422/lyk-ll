package com.example.starter.web.dto;

import java.math.BigDecimal;

/**
 * 储罐台账视图（查询）。
 *
 * @param tankId           储罐ID
 * @param stationCode      绑定门站编码
 * @param tankCode         储罐编码
 * @param capacityKg       罐容量 kg
 * @param safetyReserveKg  安全储备 kg
 * @param balanceKg        当前余额 kg
 * @param reservedKg       预占中数量 kg
 * @param availableKg      可用量 kg = 余额 − 预占
 * @param version          当前乐观锁版本
 */
public record TankView(
        Long tankId,
        String stationCode,
        String tankCode,
        BigDecimal capacityKg,
        BigDecimal safetyReserveKg,
        BigDecimal balanceKg,
        BigDecimal reservedKg,
        BigDecimal availableKg,
        long version) {
}
