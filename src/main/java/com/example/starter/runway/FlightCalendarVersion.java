package com.example.starter.runway;

import java.time.Instant;

/**
 * 合成航班日历版本行：对应 flight_calendar_version 表。
 *
 * @param version     版本号，从1开始递增
 * @param publishedAt 版本发布时间（UTC）
 * @param requestId   发布幂等请求标识
 * @param createdAt   记录创建时间（UTC）
 */
public record FlightCalendarVersion(
        int version,
        Instant publishedAt,
        String requestId,
        Instant createdAt) {
}
