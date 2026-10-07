package com.example.starter.web.dto;

import com.example.starter.idempotent.Replayable;

import java.math.BigDecimal;

/**
 * 储罐登记结果。
 *
 * @param tankId           储罐ID
 * @param stationCode      绑定门站编码
 * @param tankCode         储罐编码
 * @param varietyNo        加臭剂品种号（不可变），未启用调拨为 null
 * @param capacityKg       罐容量 kg
 * @param safetyReserveKg  安全储备 kg
 * @param balanceKg        当前余额 kg
 * @param reservedKg       预占中数量 kg（登记后恒为 0）
 * @param version          乐观锁版本，登记成功为 1
 * @param idempotentReplay 是否为同键同参重放
 */
public record TankRegisterResponse(
        Long tankId,
        String stationCode,
        String tankCode,
        String varietyNo,
        BigDecimal capacityKg,
        BigDecimal safetyReserveKg,
        BigDecimal balanceKg,
        BigDecimal reservedKg,
        long version,
        boolean idempotentReplay) implements Replayable<TankRegisterResponse> {

    @Override
    public TankRegisterResponse withReplay(boolean replay) {
        return new TankRegisterResponse(tankId, stationCode, tankCode, varietyNo, capacityKg,
                safetyReserveKg, balanceKg, reservedKg, version, replay);
    }
}
