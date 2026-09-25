package com.example.starter.payment.support;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

/**
 * UTC 16:00 日切归属计算。
 * 交易时刻早于 16:00 归入当日批次；达到或晚于 16:00（含 16:00:00 整点）归入次日批次。
 */
public final class Cutoffs {

    /** 日切时刻：UTC 每日 16:00。 */
    public static final int CUTOFF_HOUR = 16;

    private Cutoffs() {
    }

    /**
     * 计算交易时刻所属批次日。
     *
     * @param occurTime 交易发生时刻（UTC）
     * @return 批次归属日（UTC）
     */
    public static LocalDate batchDate(Instant occurTime) {
        LocalDateTime utc = LocalDateTime.ofInstant(occurTime, ZoneOffset.UTC);
        if (utc.getHour() >= CUTOFF_HOUR) {
            return utc.toLocalDate().plusDays(1);
        }
        return utc.toLocalDate();
    }
}
