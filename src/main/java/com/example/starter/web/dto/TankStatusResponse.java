package com.example.starter.web.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * 储罐台账查询结果：当前量值（余额/预占/可用/版本）与逐笔守恒流水。
 *
 * @param tankId          储罐ID
 * @param stationCode     门站编码
 * @param tankCode        储罐编码
 * @param capacityKg      容量 kg
 * @param safetyReserveKg 安全储备 kg
 * @param balanceKg       当前余额 kg
 * @param reservedKg      当前预占 kg
 * @param availableKg     当前可用量 kg
 * @param version         当前版本号
 * @param ledger          按时间顺序的台账流水
 */
public record TankStatusResponse(
        Long tankId,
        String stationCode,
        String tankCode,
        BigDecimal capacityKg,
        BigDecimal safetyReserveKg,
        BigDecimal balanceKg,
        BigDecimal reservedKg,
        BigDecimal availableKg,
        Integer version,
        List<TankLedgerItem> ledger) {
}
