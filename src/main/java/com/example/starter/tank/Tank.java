package com.example.starter.tank;

import java.math.BigDecimal;

/**
 * 储罐：绑定门站的合成加臭剂储罐。
 *
 * @param id              储罐ID
 * @param stationId       绑定门站ID（一个门站至多一个储罐）
 * @param tankCode        储罐唯一编码
 * @param capacityKg      罐容量 kg，三位小数
 * @param safetyReserveKg 安全储备 kg，0≤安全储备<容量
 * @param balanceKg       当前余额 kg，三位小数
 * @param reservedKg      预占中数量 kg（未执行/未取消计划合计）
 * @param version         乐观锁版本，从 1 开始，成功变更加 1
 */
public record Tank(Long id, Long stationId, String tankCode, BigDecimal capacityKg,
                   BigDecimal safetyReserveKg, BigDecimal balanceKg, BigDecimal reservedKg,
                   long version) {

    /**
     * 可用量 kg = 余额 − 预占中数量。
     */
    public BigDecimal availableKg() {
        return balanceKg.subtract(reservedKg);
    }
}
