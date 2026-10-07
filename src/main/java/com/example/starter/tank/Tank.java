package com.example.starter.tank;

import java.math.BigDecimal;

/**
 * 储罐：绑定门站的合成加臭剂储罐。
 *
 * @param id                  储罐ID
 * @param stationId           绑定门站ID（一个门站至多一个储罐）
 * @param tankCode            储罐唯一编码
 * @param varietyNo           加臭剂品种号，登记后不可变；null 表示未启用跨罐调拨
 * @param capacityKg          罐容量 kg，三位小数
 * @param safetyReserveKg     安全储备 kg，0≤安全储备<容量
 * @param balanceKg           当前余额 kg，三位小数
 * @param reservedKg          预占中数量 kg（未执行/未取消计划与调拨出预占合计）
 * @param incomingReservedKg  接收预占 kg（未入账调拨入预占合计，不计入可用量）
 * @param version             乐观锁版本，从 1 开始，成功变更加 1
 */
public record Tank(Long id, Long stationId, String tankCode, String varietyNo,
                   BigDecimal capacityKg, BigDecimal safetyReserveKg, BigDecimal balanceKg,
                   BigDecimal reservedKg, BigDecimal incomingReservedKg, long version) {

    /**
     * 可用量 kg = 余额 − 预占中数量；接收预占未入账，不计入可用量。
     */
    public BigDecimal availableKg() {
        return balanceKg.subtract(reservedKg);
    }
}
