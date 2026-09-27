package com.example.starter.web.dto;

import com.example.starter.idempotent.Replayable;

import java.math.BigDecimal;

/**
 * 加注执行回执结果。
 *
 * @param planId           计划ID
 * @param stationCode      门站编码
 * @param requiredKg       原预占量 kg
 * @param consumedKg       实际消耗 kg
 * @param releasedKg       本次释放的未用预占 kg
 * @param balanceKg        回执后余额 kg
 * @param reservedKg       回执后预占 kg
 * @param availableKg      回执后可用量 kg
 * @param version          储罐当前版本号
 * @param idempotentReplay 是否同键同参重放（重复回执不重复扣量）
 */
public record InjectionReceiptResponse(
        Long planId,
        String stationCode,
        BigDecimal requiredKg,
        BigDecimal consumedKg,
        BigDecimal releasedKg,
        BigDecimal balanceKg,
        BigDecimal reservedKg,
        BigDecimal availableKg,
        Integer version,
        boolean idempotentReplay) implements Replayable<InjectionReceiptResponse> {

    @Override
    public InjectionReceiptResponse withReplay(boolean replay) {
        return new InjectionReceiptResponse(planId, stationCode, requiredKg, consumedKg, releasedKg,
                balanceKg, reservedKg, availableKg, version, replay);
    }
}
