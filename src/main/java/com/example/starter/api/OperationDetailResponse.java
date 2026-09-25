package com.example.starter.api;

import java.math.BigDecimal;
import java.util.List;

/**
 * 潜次可复算详情：减压剖面、水下记录（速率/告警/配对状态）、累计时长台账与事件。
 * 供“减压剖面、速率告警与累计时长台账均可复算”核对使用。
 */
public record OperationDetailResponse(
        String operationNo,
        String status,
        int depthM,
        int bottomMinutes,
        int planVersion,
        Long exitAt,
        Long observationDueAt,
        Long chamberAt,
        Long closedAt,
        List<PlanProfileStep> profile,
        List<SegmentView> segments,
        List<LedgerView> ledger,
        List<EventView> events) {

    /** 水下记录视图：frozen=TRUE 表示方案变更已冻结、不沿用。 */
    public record SegmentView(
            String diverId,
            String phase,
            int startDepthM,
            int endDepthM,
            long startedAt,
            long endedAt,
            long durationSeconds,
            BigDecimal rateMPerMin,
            boolean rateAlarm,
            String pairState,
            int planVersion,
            boolean frozen) {
    }

    /** 台账视图：单个配对组每入一名潜水员一行。 */
    public record LedgerView(String diverId, long startedAt, long endedAt, long durationSeconds) {
    }

    /** 事件视图：RATE_ALARM/PLAN_CHANGED/CHAMBER_ACTIVATED/VITALS_LATE/CLOSED 等。 */
    public record EventView(String eventType, String detail, long createdAt) {
    }
}
