package com.example.starter.runway;

import java.time.Instant;

/**
 * 合成航班占用窗：半开区间 [startAt, endAt)，UTC。
 *
 * @param id       占用窗主键
 * @param version  所属日历版本号
 * @param runwayId 跑道编号
 * @param flightNo 合成航班号（合法文本，允许包含任意分隔符）
 * @param startAt  占用开始时刻（UTC，含）
 * @param endAt    占用结束时刻（UTC，不含）
 */
public record FlightOccupancy(
        long id,
        int version,
        String runwayId,
        String flightNo,
        Instant startAt,
        Instant endAt) {

    /**
     * 与半开区间 [from, to) 是否正长度相交：端点相接（end==from 或 to==start）不算相交。
     */
    public boolean overlaps(Instant from, Instant to) {
        return startAt.isBefore(to) && endAt.isAfter(from);
    }
}
