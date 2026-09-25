package com.example.starter.radio.model;

import java.time.LocalDate;

/**
 * 活度计日检记录。deviationRatio 为相对偏差（实测-标称）/标称；
 * 绝对值恰 0.05 判定通过，超过 0.05 当日禁用。
 */
public record DailyCheck(
        long id,
        String requestId,
        String fingerprint,
        long calibratorId,
        LocalDate checkDate,
        double referenceActivityBq,
        double measuredActivityBq,
        double deviationRatio,
        boolean passed) {
}
