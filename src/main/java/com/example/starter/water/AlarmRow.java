package com.example.starter.water;

import java.time.Instant;

/**
 * 告警行记录；metric 为越限指标（dissolved_oxygen/ammonia_nitrogen/temperature，多项以逗号连接），
 * resolvedAt 为空表示告警仍生效。
 */
public record AlarmRow(
        long id,
        long tankId,
        AlarmLevel level,
        String metric,
        String message,
        AlarmStatus status,
        Instant createdAt,
        Instant resolvedAt) {
}
