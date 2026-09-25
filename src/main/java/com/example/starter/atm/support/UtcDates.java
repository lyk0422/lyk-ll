package com.example.starter.atm.support;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;

/**
 * UTC 日界工具：业务所有“日”均按 UTC 切分，格式 yyyy-MM-dd。
 */
public final class UtcDates {

    private UtcDates() {
    }

    /** epoch 毫秒 -> UTC 日期字符串 yyyy-MM-dd。 */
    public static String dateOf(long epochMillis) {
        return Instant.ofEpochMilli(epochMillis).atOffset(ZoneOffset.UTC).toLocalDate().toString();
    }

    /** 指定 UTC 日期字符串 -> 该日 00:00:00 的 epoch 毫秒。 */
    public static long startOfDayMillis(String utcDate) {
        return LocalDate.parse(utcDate).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli();
    }

    /** 发生日 + n 个自然日的 UTC 日期字符串。 */
    public static String plusDays(String utcDate, int days) {
        return LocalDate.parse(utcDate).plusDays(days).toString();
    }

    public static String plusDays(long epochMillis, int days) {
        return Instant.ofEpochMilli(epochMillis).atOffset(ZoneOffset.UTC)
                .toLocalDate().plusDays(days).toString();
    }

    /** 截止 UTC 日 23:59:59.999 的 epoch 毫秒（恰第 n 日全天受理合法）。 */
    public static long endOfDateMillis(String utcDate) {
        return LocalDate.parse(utcDate).plusDays(1).atStartOfDay(ZoneOffset.UTC)
                .toInstant().toEpochMilli() - 1;
    }
}
