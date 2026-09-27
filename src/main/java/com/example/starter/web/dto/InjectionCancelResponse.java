package com.example.starter.web.dto;

import com.example.starter.idempotent.Replayable;

import java.math.BigDecimal;

/**
 * 加注计划取消结果。
 *
 * @param planId           计划ID
 * @param stationCode      门站编码
 * @param requiredKg       原预占量 kg
 * @param releasedKg       本次释放的未执行预占 kg
 * @param balanceKg        取消后余额 kg
 * @param reservedKg       取消后预占 kg
 * @param availableKg      取消后可用量 kg
 * @param version          储罐当前版本号
 * @param idempotentReplay 是否同键同参重放
 */
public record InjectionCancelResponse(
        Long planId,
        String stationCode,
        BigDecimal requiredKg,
        BigDecimal releasedKg,
        BigDecimal balanceKg,
        BigDecimal reservedKg,
        BigDecimal availableKg,
        Integer version,
        boolean idempotentReplay) implements Replayable<InjectionCancelResponse> {

    @Override
    public InjectionCancelResponse withReplay(boolean replay) {
        return new InjectionCancelResponse(planId, stationCode, requiredKg, releasedKg,
                balanceKg, reservedKg, availableKg, version, replay);
    }
}
