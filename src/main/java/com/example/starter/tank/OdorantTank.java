package com.example.starter.tank;

import java.math.BigDecimal;

/**
 * 加臭剂储罐。
 *
 * @param id              主键
 * @param stationId       绑定门站ID，一门站至多一个储罐
 * @param tankCode        合成储罐编码，全局唯一
 * @param capacityKg      容量 kg，三位小数
 * @param safetyReserveKg 安全储备 kg，0≤安全储备&lt;容量
 * @param balanceKg       余额 kg（含已预占部分）
 * @param reservedKg      已预占未结算 kg，可用量=余额-预占
 * @param version         乐观锁版本，登记为 1，每次成功变更加 1
 */
public record OdorantTank(
        Long id,
        Long stationId,
        String tankCode,
        BigDecimal capacityKg,
        BigDecimal safetyReserveKg,
        BigDecimal balanceKg,
        BigDecimal reservedKg,
        int version) {

    /**
     * 当前可用量 = 余额 − 预占。
     */
    public BigDecimal availableKg() {
        return balanceKg.subtract(reservedKg);
    }
}
