package com.example.starter.dto;

import java.time.Instant;
import java.util.List;

/**
 * 受控分段指令登记/重放响应。
 * 回传冻结的分段计划（版本 1、启动 UTC 时刻、期限）与各机组斜率；
 * replayed=true 表示同键同参重放，内容与首次冻结结果一致。
 */
public record StagedOrderReceiveResponse(
        Long orderId,
        String orderNo,
        String requestId,
        String farmCode,
        int targetPowerKw,
        int farmAvailableKw,
        int curtailedKw,
        String status,
        Long planId,
        int planVersion,
        String planStatus,
        Instant startAt,
        int durationMinutes,
        List<StagedTurbineView> turbines,
        List<AllocationItem> allocations,
        boolean replayed) {

    public StagedOrderReceiveResponse asReplay() {
        return new StagedOrderReceiveResponse(orderId, orderNo, requestId, farmCode, targetPowerKw,
                farmAvailableKw, curtailedKw, status, planId, planVersion, planStatus, startAt,
                durationMinutes, turbines, allocations, true);
    }
}
