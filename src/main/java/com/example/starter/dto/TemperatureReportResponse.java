package com.example.starter.dto;

import java.time.Instant;

/**
 * 温度上报响应。
 *
 * @param requestId  请求幂等键
 * @param accepted   入库读数条数
 * @param late       是否因任务已归档而记为迟到事件
 * @param receivedAt 服务端接收时刻(UTC)
 * @param replay     是否为同键同参重放返回
 */
public record TemperatureReportResponse(
        String requestId,
        int accepted,
        boolean late,
        Instant receivedAt,
        boolean replay
) {
}
