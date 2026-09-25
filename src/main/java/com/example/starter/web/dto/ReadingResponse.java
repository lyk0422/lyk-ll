package com.example.starter.web.dto;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 抄表处理结果。
 *
 * @param requestId        幂等键
 * @param stationCode      基站编号
 * @param readAt           抄表时刻，UTC
 * @param remainingWh      剩余容量，单位 Wh
 * @param estimatedMinutes 估算可支撑分钟数
 * @param deduped          true 表示同基站同抄表时刻重复上报被去重
 * @param latest           true 表示按抄表时刻归位后该读数为最新一条
 */
public record ReadingResponse(
        String requestId,
        String stationCode,
        Instant readAt,
        BigDecimal remainingWh,
        int estimatedMinutes,
        boolean deduped,
        boolean latest
) {
}
