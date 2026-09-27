package com.example.starter.runway;

import java.time.Instant;

/**
 * 航班日历当前版本指针行：对应 flight_calendar 单行表。
 *
 * @param id          固定为1
 * @param version     日历版本号，从1开始，每次成功发布加1
 * @param publishedAt 当前版本发布请求携带的精确时刻（UTC）
 * @param updatedAt   记录更新时间（UTC）
 */
public record FlightCalendarRecord(
        int id,
        int version,
        Instant publishedAt,
        Instant updatedAt) {
}
