package com.example.starter.tank;

import java.math.BigDecimal;

/**
 * 加臭剂储罐聚合（一行即一罐，一站一罐）。
 *
 * @param id              储罐ID
 * @param stationId       绑定门站ID
 * @param capacityKg      容量 kg（三位小数）
 * @param safetyReserveKg 安全储备 kg，0 ≤ 安全储备 < 容量
 * @param balanceKg       当前余额 kg（含预占中部分）
 * @param reservedKg      预占中 kg
 * @param version         乐观锁版本，登记为 1，每次成功变更加 1
 */
public record Tank(
        Long id,
        Long stationId,
        BigDecimal capacityKg,
        BigDecimal safetyReserveKg,
        BigDecimal balanceKg,
        BigDecimal reservedKg,
        long version) {

    /**
     * 可用量 kg = 余额 − 预占；预占后可用量不得低于安全储备（恰等允许）。
     */
    public BigDecimal availableKg() {
        return balanceKg.subtract(reservedKg);
    }
}
