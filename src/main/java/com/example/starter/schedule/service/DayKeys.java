package com.example.starter.schedule.service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;

/**
 * 按 UTC 日期切分编排日。
 */
public final class DayKeys {

    private DayKeys() {
    }

    /**
     * 返回时刻对应的 UTC 日期键 yyyy-MM-dd。
     */
    public static String of(Instant instant) {
        return LocalDate.ofInstant(instant, ZoneOffset.UTC).toString();
    }
}
