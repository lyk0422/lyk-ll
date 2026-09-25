package com.example.starter.cems.service;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;

/**
 * 时段工具：小时/自然日整点校验与取整。
 */
public final class PeriodUtil {

    private PeriodUtil() {
    }

    /** 取整到小时整点。 */
    public static LocalDateTime floorHour(LocalDateTime t) {
        return t.truncatedTo(ChronoUnit.HOURS);
    }

    /** 取整到自然日零点（Asia/Shanghai 业务时区）。 */
    public static LocalDateTime floorDay(LocalDateTime t) {
        return t.toLocalDate().atStartOfDay();
    }

    /** 是否整点。 */
    public static boolean isHourStart(LocalDateTime t) {
        return t.equals(floorHour(t));
    }

    /** 是否零点。 */
    public static boolean isDayStart(LocalDateTime t) {
        return t.equals(floorDay(t));
    }
}
