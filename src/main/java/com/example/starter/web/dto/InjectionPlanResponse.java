package com.example.starter.web.dto;

import com.example.starter.idempotent.Replayable;

import java.math.BigDecimal;

/**
 * 受控加注计划结果。
 *
 * @param planId           计划ID
 * @param status           RESERVED 已预占 / BLOCKED 安全储备不足未下发
 * @param plannedKg        预占量 kg
 * @param dispatched       是否已下发加注（BLOCKED 时为 false，不伪报加注成功）
 * @param balanceKg        余额 kg
 * @param reservedKg       预占合计 kg
 * @param availableKg      可用量 kg
 * @param refillEventId    安全储备不足时生成的待补液事件ID，否则为 null
 * @param version          变动后储罐版本（BLOCKED 未变动时仍为当前版本）
 * @param idempotentReplay 是否为同键同参重放
 */
public record InjectionPlanResponse(
        Long planId,
        String status,
        BigDecimal plannedKg,
        boolean dispatched,
        BigDecimal balanceKg,
        BigDecimal reservedKg,
        BigDecimal availableKg,
        Long refillEventId,
        int version,
        boolean idempotentReplay) implements Replayable<InjectionPlanResponse> {

    @Override
    public InjectionPlanResponse withReplay(boolean replay) {
        return new InjectionPlanResponse(planId, status, plannedKg, dispatched, balanceKg,
                reservedKg, availableKg, refillEventId, version, replay);
    }
}
