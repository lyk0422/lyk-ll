package com.example.starter.web.dto;

import com.example.starter.idempotent.Replayable;

import java.math.BigDecimal;

/**
 * 受控加注计划下达结果。
 *
 * @param planId           计划ID
 * @param planRequestId    计划单号（即下达请求幂等键）
 * @param status           PREOCCUPIED 已预占 / REJECTED 储备不足未下发
 * @param dispatched       是否已下发加注（储备不足时为 false，不伪报加注成功）
 * @param requiredKg       计划预占量 kg
 * @param availableAfterKg 处理后可用量 kg = 余额 − 预占
 * @param refillEventId    储备不足时生成的待补液事件ID，正常下发为 null
 * @param version          处理后储罐版本号（REJECTED 未改储罐，版本不变）
 * @param idempotentReplay 是否为同键同参重放
 */
public record InjectionPlanResponse(
        Long planId,
        String planRequestId,
        String status,
        boolean dispatched,
        BigDecimal requiredKg,
        BigDecimal availableAfterKg,
        Long refillEventId,
        long version,
        boolean idempotentReplay) implements Replayable<InjectionPlanResponse> {

    @Override
    public InjectionPlanResponse withReplay(boolean replay) {
        return new InjectionPlanResponse(planId, planRequestId, status, dispatched, requiredKg,
                availableAfterKg, refillEventId, version, replay);
    }
}
