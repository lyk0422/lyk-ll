package com.example.starter.web.dto;

import com.example.starter.idempotent.Replayable;

import java.math.BigDecimal;

/**
 * 加注计划受理结果。
 *
 * @param planId           计划ID
 * @param tankId           储罐ID
 * @param status           RESERVED 已预占并下发 / BLOCKED 可用量低于安全储备未下发
 * @param dispatched       是否已下发加注（BLOCKED 为 false，不得伪报加注成功）
 * @param requiredQtyKg    计划预占量 kg
 * @param balanceKg        操作后余额 kg
 * @param reservedKg       操作后预占中数量 kg
 * @param availableKg      操作后可用量 kg = 余额 − 预占
 * @param refillEventId    生成的待补液事件ID，未触发为 null
 * @param version          操作后储罐版本
 * @param idempotentReplay 是否为同键同参重放
 */
public record TankPlanResponse(
        Long planId,
        Long tankId,
        String status,
        boolean dispatched,
        BigDecimal requiredQtyKg,
        BigDecimal balanceKg,
        BigDecimal reservedKg,
        BigDecimal availableKg,
        Long refillEventId,
        long version,
        boolean idempotentReplay) implements Replayable<TankPlanResponse> {

    @Override
    public TankPlanResponse withReplay(boolean replay) {
        return new TankPlanResponse(planId, tankId, status, dispatched, requiredQtyKg, balanceKg,
                reservedKg, availableKg, refillEventId, version, replay);
    }
}
