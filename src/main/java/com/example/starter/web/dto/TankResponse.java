package com.example.starter.web.dto;

import com.example.starter.idempotent.Replayable;

import java.math.BigDecimal;

/**
 * 储罐登记/配置调整结果。
 *
 * @param tankId           储罐ID
 * @param stationCode      门站编码
 * @param capacityKg       容量 kg
 * @param safetyReserveKg  安全储备 kg
 * @param balanceKg        当前余额 kg
 * @param reservedKg       预占中 kg
 * @param availableKg      可用量 kg = 余额 − 预占
 * @param version          当前版本号（登记为 1，每次成功变更加 1）
 * @param idempotentReplay 是否为同键同参重放
 */
public record TankResponse(
        Long tankId,
        String stationCode,
        BigDecimal capacityKg,
        BigDecimal safetyReserveKg,
        BigDecimal balanceKg,
        BigDecimal reservedKg,
        BigDecimal availableKg,
        long version,
        boolean idempotentReplay) implements Replayable<TankResponse> {

    @Override
    public TankResponse withReplay(boolean replay) {
        return new TankResponse(tankId, stationCode, capacityKg, safetyReserveKg, balanceKg,
                reservedKg, availableKg, version, replay);
    }
}
