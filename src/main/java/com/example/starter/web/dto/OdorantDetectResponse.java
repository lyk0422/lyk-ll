package com.example.starter.web.dto;

import com.example.starter.idempotent.Replayable;

import java.math.BigDecimal;

/**
 * 浓度检测处理结果。
 *
 * @param qualified        浓度是否合格（含恰在区间边界）
 * @param breachCount      检测后当前连续越限次数
 * @param injectionLocked  检测后加注阀是否锁定
 * @param alarmActive      检测后告警是否生效
 * @param lowerBoundMgM3   合格区间下限 mg/m³
 * @param upperBoundMgM3   合格区间上限 mg/m³
 * @param late             TRUE 表示日结封存后迟到，仅记事件不联动、不计统计
 * @param idempotentReplay 是否为同键同参重放
 */
public record OdorantDetectResponse(
        boolean qualified,
        int breachCount,
        boolean injectionLocked,
        boolean alarmActive,
        BigDecimal lowerBoundMgM3,
        BigDecimal upperBoundMgM3,
        boolean late,
        boolean idempotentReplay) implements Replayable<OdorantDetectResponse> {

    @Override
    public OdorantDetectResponse withReplay(boolean replay) {
        return new OdorantDetectResponse(qualified, breachCount, injectionLocked, alarmActive,
                lowerBoundMgM3, upperBoundMgM3, late, replay);
    }
}
