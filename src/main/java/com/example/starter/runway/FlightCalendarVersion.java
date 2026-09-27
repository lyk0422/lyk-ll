package com.example.starter.runway;

import java.time.Instant;

/**
 * 航班日历版本行：对应 flight_calendar 表。版本仅追加，从1开始，每次成功发布加1。
 *
 * @param id          日历版本行主键
 * @param version     日历版本号（从1单调递增）
 * @param publishedAt 该版本生效时刻（UTC）
 * @param requestId   发布幂等请求标识；初始种子版本为 null
 * @param createdAt   记录创建时间（UTC）
 */
public record FlightCalendarVersion(
        long id,
        int version,
        Instant publishedAt,
        String requestId,
        Instant createdAt) {
}
