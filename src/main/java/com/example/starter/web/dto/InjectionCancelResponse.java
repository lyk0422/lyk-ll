package com.example.starter.web.dto;

import com.example.starter.idempotent.Replayable;

import java.math.BigDecimal;

/**
 * 加注计划取消结果。
 *
 * @param planId           计划ID
 * @param status           CANCELLED
 * @param releasedKg       释放的预占 kg
 * @param balanceKg        余额 kg
 * @param reservedKg       预占合计 kg
 * @param version          变动后储罐版本
 * @param idempotentReplay 是否为同键同参重放
 */
public record InjectionCancelResponse(
        Long planId,
        String status,
        BigDecimal releasedKg,
        BigDecimal balanceKg,
        BigDecimal reservedKg,
        int version,
        boolean idempotentReplay) implements Replayable<InjectionCancelResponse> {

    @Override
    public InjectionCancelResponse withReplay(boolean replay) {
        return new InjectionCancelResponse(planId, status, releasedKg, balanceKg,
                reservedKg, version, replay);
    }
}
