package com.example.starter.dto;

import java.time.Instant;
import java.util.List;

/**
 * 指令详情视图：含各轮分摊明细、执行曲线（按机组＋时刻归位排序）与告警链，可复算。
 */
public record OrderDetailResponse(
        Long orderId,
        String orderNo,
        String farmCode,
        String requestId,
        int targetPowerKw,
        String status,
        int currentCycleNo,
        Instant receivedAt,
        Instant settledAt,
        List<CycleView> cycles,
        List<ReportPoint> executionCurve,
        List<AlertView> alerts,
        List<LateReportView> lateReports) {

    /** 一轮分摊下发：时刻与该轮各机组分摊明细。 */
    public record CycleView(
            int cycleNo,
            Instant dispatchedAt,
            Instant dueAt,
            List<AllocationItem> allocations) {
    }

    /** 执行曲线单点：按机组＋时刻归位（同机组乱序回报按时刻排序）。 */
    public record ReportPoint(
            String turbineCode,
            Instant measuredAt,
            int powerKw,
            Instant receivedAt) {
    }

    /** 封存后迟到回报事件。 */
    public record LateReportView(
            String turbineCode,
            Instant measuredAt,
            Integer powerKw,
            String requestId,
            Instant recordedAt) {
    }
}
