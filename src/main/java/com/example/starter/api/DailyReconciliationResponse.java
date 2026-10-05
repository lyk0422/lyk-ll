package com.example.starter.api;

import java.time.LocalDate;
import java.util.List;

/**
 * 日结核对响应：某营业日（UTC 日界）全部快速审批通道结论留痕，
 * 用于临时计划不占用常规审批队列的核对与审计。
 */
public record DailyReconciliationResponse(
        LocalDate businessDate,
        int fastTrackDecisionCount,
        List<FastTrackDecisionResponse> fastTrackDecisions
) {
}
