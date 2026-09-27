package com.example.starter.runway;

import java.time.Instant;

/**
 * 航班日历行：对应 flight_calendar 表（合成数据，按跑道维护版本）。
 *
 * @param runwayId  跑道编号（合成数据）
 * @param version   日历版本：从1开始，每次成功变更加1
 * @param updatedAt 最近变更时间（UTC）
 */
public record FlightCalendar(
        String runwayId,
        int version,
        Instant updatedAt) {
}
