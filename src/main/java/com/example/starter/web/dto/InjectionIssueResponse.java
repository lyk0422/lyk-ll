package com.example.starter.web.dto;

import com.example.starter.idempotent.Replayable;

import java.math.BigDecimal;

/**
 * 受控加注计划下达结果。
 *
 * @param planId            加注计划ID（被安全储备拦截未下发时为 null）
 * @param stationCode       门站编码
 * @param issued            是否成功预占并下发；false 表示生成待补液事件、加注不下发
 * @param requiredKg        预占所需量 kg
 * @param balanceKg         下达后余额 kg（拦截时不变）
 * @param reservedKg        下达后预占 kg（拦截时不变）
 * @param availableKg       下达后可用量 kg
 * @param safetyReserveKg   安全储备 kg
 * @param pendingEventCreated 是否生成了待补液事件
 * @param version           储罐当前版本号
 * @param idempotentReplay  是否同键同参重放
 */
public record InjectionIssueResponse(
        Long planId,
        String stationCode,
        boolean issued,
        BigDecimal requiredKg,
        BigDecimal balanceKg,
        BigDecimal reservedKg,
        BigDecimal availableKg,
        BigDecimal safetyReserveKg,
        boolean pendingEventCreated,
        Integer version,
        boolean idempotentReplay) implements Replayable<InjectionIssueResponse> {

    @Override
    public InjectionIssueResponse withReplay(boolean replay) {
        return new InjectionIssueResponse(planId, stationCode, issued, requiredKg, balanceKg,
                reservedKg, availableKg, safetyReserveKg, pendingEventCreated, version, replay);
    }
}
