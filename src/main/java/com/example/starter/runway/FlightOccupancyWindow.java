package com.example.starter.runway;

import java.time.Instant;

/**
 * 合成航班占用窗行：对应 flight_occupancy 表。区间为半开 [start, end)。
 *
 * @param id          占用窗主键
 * @param version     所属航班日历版本号
 * @param runwayId    跑道编号（合成数据）
 * @param windowStart 占用开始时刻（UTC，含）
 * @param windowEnd   占用结束时刻（UTC，不含）
 */
public record FlightOccupancyWindow(
        long id,
        int version,
        String runwayId,
        Instant windowStart,
        Instant windowEnd) {
}
