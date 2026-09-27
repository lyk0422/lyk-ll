package com.example.starter.dto;

import java.time.LocalDate;
import java.util.List;

/**
 * 计划绑定模式限电指令受理/重放响应。
 * 与旧模式响应并列：额外回传冻结的版本号、UTC 日期与时段序号。
 * replayed=true 表示同键同参重放，内容与首次冻结结果一致。
 */
public record PlanOrderReceiveResponse(
        Long orderId,
        String orderNo,
        String requestId,
        String farmCode,
        int targetPowerKw,
        int farmParticipatingKw,
        int curtailedKw,
        String status,
        int cycleNo,
        boolean noCurtailment,
        Long planVersionId,
        int planVersionNo,
        LocalDate planDate,
        int segmentIndex,
        boolean versionCurrent,
        List<AllocationItem> allocations,
        boolean replayed) {

    public PlanOrderReceiveResponse asReplay() {
        return new PlanOrderReceiveResponse(orderId, orderNo, requestId, farmCode, targetPowerKw,
                farmParticipatingKw, curtailedKw, status, cycleNo, noCurtailment,
                planVersionId, planVersionNo, planDate, segmentIndex, versionCurrent,
                allocations, true);
    }
}
