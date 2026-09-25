package com.example.starter.lc.support;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;

/**
 * 合成银行日历（UTC）：银行工作日为周一至周五，不设银行假日。
 * 审单期自银行收到单据次日起算 5 个银行工作日，拒付通知截止时刻为第 5 个工作日 17:00（UTC）。
 */
public final class BankCalendar {

    /** 审单银行工作日个数（UCP 口径：收到单据次日起 5 个工作日）。 */
    public static final int REVIEW_WORKDAYS = 5;

    /** 第 5 个工作日拒付通知的截止时刻（小时，UTC），恰在 17:00 前发出有效。 */
    public static final int DEADLINE_HOUR = 17;

    private BankCalendar() {
    }

    /**
     * 判断给定时刻是否为银行工作日（UTC 周一至周五）。
     */
    public static boolean isBankingDay(Instant instant) {
        DayOfWeek day = LocalDate.ofInstant(instant, ZoneOffset.UTC).getDayOfWeek();
        return day != DayOfWeek.SATURDAY && day != DayOfWeek.SUNDAY;
    }

    /**
     * 计算审单期截止时刻：收到单据次日起第 5 个银行工作日的 17:00（UTC）。
     *
     * @param receivedAt 银行收到单据时刻（UTC）
     * @return 拒付通知有效发出的最晚时刻边界（17:00 本身及之后无效）
     */
    public static Instant refusalDeadline(Instant receivedAt) {
        LocalDate nextDay = LocalDate.ofInstant(receivedAt, ZoneOffset.UTC).plusDays(1);
        LocalDate day = nextDay;
        int counted = 0;
        while (counted < REVIEW_WORKDAYS) {
            DayOfWeek dow = day.getDayOfWeek();
            if (dow != DayOfWeek.SATURDAY && dow != DayOfWeek.SUNDAY) {
                counted++;
            }
            if (counted < REVIEW_WORKDAYS) {
                day = day.plusDays(1);
            }
        }
        return day.atTime(DEADLINE_HOUR, 0).toInstant(ZoneOffset.UTC);
    }

    /**
     * 判断拒付通知发出时刻是否有效：必须严格早于截止时刻（恰在 17:00 前有效，17:00 及之后无效）。
     */
    public static boolean isRefusalTimely(Instant refusedAt, Instant deadline) {
        return refusedAt.isBefore(deadline);
    }
}
