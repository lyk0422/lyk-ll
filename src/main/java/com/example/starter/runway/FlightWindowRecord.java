package com.example.starter.runway;

import java.time.Instant;

/**
 * 航班占用窗快照行：对应 flight_window 表，按日历版本冻结、不可变。
 * 区间为 UTC 半开 [occupyStart, occupyEnd)，与巡检区间正长度相交即冲突，端点相接合法。
 *
 * @param id            占用窗主键
 * @param calendarVersion 所属日历版本
 * @param runwayId      跑道编号（合成数据）
 * @param flightNo      合成航班号
 * @param occupyStart   占用开始时刻（UTC，含）
 * @param occupyEnd     占用结束时刻（UTC，不含）
 * @param createdAt     记录创建时间（UTC）
 */
public record FlightWindowRecord(
        long id,
        int calendarVersion,
        String runwayId,
        String flightNo,
        Instant occupyStart,
        Instant occupyEnd,
        Instant createdAt) {
}
