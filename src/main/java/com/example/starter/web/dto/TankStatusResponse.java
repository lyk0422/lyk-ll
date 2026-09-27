package com.example.starter.web.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * 储罐状态查询结果（只读）。
 *
 * @param tankId              储罐ID
 * @param stationCode         门站编码
 * @param capacityKg          容量 kg
 * @param safetyReserveKg     安全储备 kg
 * @param balanceKg           当前余额 kg
 * @param reservedKg          预占中 kg
 * @param availableKg         可用量 kg = 余额 − 预占
 * @param version             当前版本号
 * @param pendingRefillEvents 待补液事件ID列表
 */
public record TankStatusResponse(
        Long tankId,
        String stationCode,
        BigDecimal capacityKg,
        BigDecimal safetyReserveKg,
        BigDecimal balanceKg,
        BigDecimal reservedKg,
        BigDecimal availableKg,
        long version,
        List<Long> pendingRefillEvents) {
}
