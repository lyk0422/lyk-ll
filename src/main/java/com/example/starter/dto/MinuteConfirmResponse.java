package com.example.starter.dto;

import java.time.Instant;
import java.util.List;

/**
 * 受控分段整分钟确认结果。
 * executed=false 表示该分钟未执行（如机组失效导致计划暂停），已执行历史不回退。
 */
public record MinuteConfirmResponse(
        String orderNo,
        String requestId,
        int planVersion,
        int minuteNo,
        Instant scheduledAt,
        Instant confirmedAt,
        boolean executed,
        String planStatus,
        String orderStatus,
        int confirmedMinute,
        int durationMinutes,
        boolean completed,
        List<MinuteReportItem> reports,
        boolean replayed) implements ReplayableResponse<MinuteConfirmResponse> {

    @Override
    public MinuteConfirmResponse asReplay() {
        return new MinuteConfirmResponse(orderNo, requestId, planVersion, minuteNo, scheduledAt,
                confirmedAt, executed, planStatus, orderStatus, confirmedMinute, durationMinutes,
                completed, reports, true);
    }
}
