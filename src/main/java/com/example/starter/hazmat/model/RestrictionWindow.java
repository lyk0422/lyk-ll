package com.example.starter.hazmat.model;

import java.time.LocalDateTime;

/**
 * 路线管制时段，半开区间 [startTime, endTime)，计划通过时刻恰为 endTime 视为不受管制。
 *
 * @param startTime 管制开始时刻（含）
 * @param endTime   管制结束时刻（不含）
 */
public record RestrictionWindow(LocalDateTime startTime, LocalDateTime endTime) {

    /**
     * 判定时刻是否落在管制时段内（半开区间）。
     */
    public boolean contains(LocalDateTime instant) {
        return !instant.isBefore(startTime) && instant.isBefore(endTime);
    }
}
