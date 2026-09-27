package com.example.starter.tank;

import java.math.BigDecimal;

/**
 * 加臭剂储罐。
 *
 * @param id              主键
 * @param stationId       所属门站ID，一门站至多一个储罐
 * @param tankCode        储罐编码（唯一）
 * @param capacityKg      容量 kg，三位小数
 * @param safetyReserveKg 安全储备 kg，三位小数，0 ≤ 安全储备 &lt; 容量
 * @param balanceKg       当前实际余额 kg，三位小数，0 ≤ 余额 ≤ 容量
 * @param reservedKg      已预占未结算量 kg，三位小数，可用量 = 余额 − 预占
 * @param version         乐观版本号，登记为 1，每次成功写操作加 1
 */
public record Tank(
        Long id,
        Long stationId,
        String tankCode,
        BigDecimal capacityKg,
        BigDecimal safetyReserveKg,
        BigDecimal balanceKg,
        BigDecimal reservedKg,
        Integer version) {

    /**
     * 可用量 = 余额 − 预占。
     */
    public BigDecimal availableKg() {
        return balanceKg.subtract(reservedKg);
    }
}
