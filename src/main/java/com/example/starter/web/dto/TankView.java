package com.example.starter.web.dto;

import java.math.BigDecimal;

/**
 * 储罐台账视图（查询）。
 *
 * @param tankId              储罐ID
 * @param stationCode         绑定门站编码
 * @param tankCode            储罐编码
 * @param varietyNo           加臭剂品种号（不可变），未启用调拨为 null
 * @param capacityKg          罐容量 kg
 * @param safetyReserveKg     安全储备 kg
 * @param balanceKg           当前余额 kg
 * @param reservedKg          预占中数量 kg
 * @param incomingReservedKg  接收预占 kg（未入账调拨入预占，不计入可用量）
 * @param availableKg         可用量 kg = 余额 − 预占
 * @param version             当前乐观锁版本
 */
public record TankView(
        Long tankId,
        String stationCode,
        String tankCode,
        String varietyNo,
        BigDecimal capacityKg,
        BigDecimal safetyReserveKg,
        BigDecimal balanceKg,
        BigDecimal reservedKg,
        BigDecimal incomingReservedKg,
        BigDecimal availableKg,
        long version) {
}
