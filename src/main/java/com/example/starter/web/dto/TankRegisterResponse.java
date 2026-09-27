package com.example.starter.web.dto;

import com.example.starter.idempotent.Replayable;

import java.math.BigDecimal;

/**
 * 储罐登记结果。
 *
 * @param tankId           储罐ID
 * @param tankCode         储罐编码
 * @param stationCode      门站编码
 * @param capacityKg       容量 kg
 * @param safetyReserveKg  安全储备 kg
 * @param balanceKg        余额 kg
 * @param reservedKg       预占 kg
 * @param version          当前版本，登记成功为 1
 * @param idempotentReplay 是否为同键同参重放
 */
public record TankRegisterResponse(
        Long tankId,
        String tankCode,
        String stationCode,
        BigDecimal capacityKg,
        BigDecimal safetyReserveKg,
        BigDecimal balanceKg,
        BigDecimal reservedKg,
        int version,
        boolean idempotentReplay) implements Replayable<TankRegisterResponse> {

    @Override
    public TankRegisterResponse withReplay(boolean replay) {
        return new TankRegisterResponse(tankId, tankCode, stationCode, capacityKg,
                safetyReserveKg, balanceKg, reservedKg, version, replay);
    }
}
