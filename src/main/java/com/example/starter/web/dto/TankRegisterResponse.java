package com.example.starter.web.dto;

import com.example.starter.idempotent.Replayable;

import java.math.BigDecimal;

/**
 * 储罐登记结果。
 *
 * @param tankId          储罐ID
 * @param stationCode     门站编码
 * @param tankCode        储罐编码
 * @param capacityKg      容量 kg
 * @param safetyReserveKg 安全储备 kg
 * @param balanceKg       余额 kg
 * @param reservedKg      预占 kg
 * @param availableKg     可用量 kg = 余额 − 预占
 * @param version         当前版本号（登记成功为 1）
 * @param idempotentReplay 是否同键同参重放
 */
public record TankRegisterResponse(
        Long tankId,
        String stationCode,
        String tankCode,
        BigDecimal capacityKg,
        BigDecimal safetyReserveKg,
        BigDecimal balanceKg,
        BigDecimal reservedKg,
        BigDecimal availableKg,
        Integer version,
        boolean idempotentReplay) implements Replayable<TankRegisterResponse> {

    @Override
    public TankRegisterResponse withReplay(boolean replay) {
        return new TankRegisterResponse(tankId, stationCode, tankCode, capacityKg, safetyReserveKg,
                balanceKg, reservedKg, availableKg, version, replay);
    }
}
