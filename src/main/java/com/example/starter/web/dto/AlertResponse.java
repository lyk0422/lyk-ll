package com.example.starter.web.dto;

import java.time.Instant;

/**
 * 告警处理结果。
 *
 * @param requestId     幂等键
 * @param eventId       停电事件 ID
 * @param stationCode   基站编号
 * @param outageStartAt 停电起始时刻，UTC
 * @param merged        true 表示本次为同事件重复告警合并，未新建事件
 * @param eventStatus   事件状态
 */
public record AlertResponse(
        String requestId,
        long eventId,
        String stationCode,
        Instant outageStartAt,
        boolean merged,
        String eventStatus
) {
}
