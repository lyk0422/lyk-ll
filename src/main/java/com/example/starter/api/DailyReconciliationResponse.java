package com.example.starter.api;

import java.time.LocalDate;
import java.util.List;

/**
 * 日结核对响应：指定 UTC 营业日的全部加钞计划与当日快速通道结论留痕。
 */
public record DailyReconciliationResponse(
        LocalDate date,
        List<PlanResponse> plans,
        List<FastTrackAuditResponse> fastTrackAudits
) {
}
