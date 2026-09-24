package com.example.starter.payment;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;

/**
 * 日切工具：交易按 UTC 每日 16:00 日切。
 * 交易时刻早于 16:00 归入当日批次；达到或晚于 16:00（含 16:00:00 整点）归入次日批次。
 */
public final class CutoffClock {

    /** 日切时刻（UTC）。 */
    public static final LocalTime CUTOFF_TIME = LocalTime.of(16, 0);

    private CutoffClock() {
    }

    /**
     * 计算交易时间归属的批次日期（UTC）。
     *
     * @param txnTime 交易时间（UTC 瞬间）
     * @return 批次日期
     */
    public static LocalDate batchDateOf(Instant txnTime) {
        var utc = txnTime.atZone(ZoneOffset.UTC);
        LocalDate date = utc.toLocalDate();
        return utc.toLocalTime().isBefore(CUTOFF_TIME) ? date : date.plusDays(1);
    }
}
