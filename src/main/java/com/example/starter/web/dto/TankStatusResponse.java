package com.example.starter.web.dto;

import java.math.BigDecimal;

/**
 * 储罐状态查询结果。
 *
 * @param tankId              储罐ID
 * @param tankCode            储罐编码
 * @param stationCode         门站编码
 * @param capacityKg          容量 kg
 * @param safetyReserveKg     安全储备 kg
 * @param balanceKg           余额 kg
 * @param reservedKg          预占 kg
 * @param availableKg         可用量 kg = 余额 − 预占
 * @param version             当前版本
 * @param pendingRefillEvents 待补液事件数
 */
public record TankStatusResponse(
        Long tankId,
        String tankCode,
        String stationCode,
        BigDecimal capacityKg,
        BigDecimal safetyReserveKg,
        BigDecimal balanceKg,
        BigDecimal reservedKg,
        BigDecimal availableKg,
        int version,
        long pendingRefillEvents) {
}
