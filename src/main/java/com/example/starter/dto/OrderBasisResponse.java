package com.example.starter.dto;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * 计划绑定指令的基数证据：冻结的版本/日期/时段与各轮（初次下发、超时重分摊）
 * 每机组核定计划、实时可用、参与出力 min(实时, 计划) 及分摊结果，可逐轮复算。
 * 未绑定计划的指令无此证据（入口返回 404）。
 */
public record OrderBasisResponse(
        Long orderId,
        String orderNo,
        String farmCode,
        Long planVersionId,
        int planVersionNo,
        LocalDate planDate,
        int segmentIndex,
        Instant segmentStart,
        Instant segmentEnd,
        boolean versionCurrent,
        List<CycleBasisView> cycles) {

    /** 一轮的冻结基数：计划值不可变（各轮一致），实时值为该轮时刻值，参与出力取两者较小者。 */
    public record CycleBasisView(
            int cycleNo,
            Instant dispatchedAt,
            List<TurbineBasisItem> items) {
    }

    public record TurbineBasisItem(
            String turbineCode,
            int plannedPowerKw,
            int realtimeAvailableKw,
            int participatingPowerKw,
            int technicalMinKw,
            int targetPowerKw,
            int curtailKw) {
    }
}
