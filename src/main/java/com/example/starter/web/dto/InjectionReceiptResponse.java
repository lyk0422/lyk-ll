package com.example.starter.web.dto;

import com.example.starter.idempotent.Replayable;

import java.math.BigDecimal;

/**
 * 加注执行回执结果。
 *
 * @param planId           计划ID
 * @param status           EXECUTED
 * @param plannedKg        预占量 kg
 * @param actualKg         实际消耗 kg
 * @param releasedKg       释放未用部分 kg = 预占 − 实际
 * @param balanceKg        余额 kg
 * @param reservedKg       预占合计 kg
 * @param version          变动后储罐版本
 * @param idempotentReplay 是否为同键同参重放
 */
public record InjectionReceiptResponse(
        Long planId,
        String status,
        BigDecimal plannedKg,
        BigDecimal actualKg,
        BigDecimal releasedKg,
        BigDecimal balanceKg,
        BigDecimal reservedKg,
        int version,
        boolean idempotentReplay) implements Replayable<InjectionReceiptResponse> {

    @Override
    public InjectionReceiptResponse withReplay(boolean replay) {
        return new InjectionReceiptResponse(planId, status, plannedKg, actualKg, releasedKg,
                balanceKg, reservedKg, version, replay);
    }
}
