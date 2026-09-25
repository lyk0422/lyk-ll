package com.example.starter.survey.service;

import java.time.LocalDate;

/**
 * UTC 日历日与年度检验窗口计算。
 *
 * <p>年度检验窗口为周年日前后各 3 个月，闭区间（恰在首日或末日完成均有效）。
 * 周年日取船舶周年日的“月-日”，在周期年份复用；窗口可能跨年（如周年日 11-30，
 * 窗口为当年 08-30 至次年 02-28）。</p>
 */
public final class SurveyDates {

    /** 窗口相对周年日的偏移月数。 */
    public static final int WINDOW_MONTHS = 3;
    /** 一般缺陷复查期限天数（发现日起第 30 日为最后受理日）。 */
    public static final int RECHECK_DAYS = 30;

    private SurveyDates() {
    }

    /** 周期年份对应的周年日（UTC，月-日复用，2 月 29 日在平年回落至 28 日）。 */
    public static LocalDate anniversaryInYear(LocalDate baseAnniversary, int year) {
        int day = baseAnniversary.getDayOfMonth();
        int maxDay = baseAnniversary.getMonth().length(year % 4 == 0);
        return LocalDate.of(year, baseAnniversary.getMonth(), Math.min(day, maxDay));
    }

    public static LocalDate windowStart(LocalDate anniversary) {
        return anniversary.minusMonths(WINDOW_MONTHS);
    }

    public static LocalDate windowEnd(LocalDate anniversary) {
        return anniversary.plusMonths(WINDOW_MONTHS);
    }

    /**
     * 判定给定日期所属的周期年份：
     * 先看上年周年日窗口是否覆盖（跨年窗口情形），否则归属当年周期。
     */
    public static int cycleYear(LocalDate baseAnniversary, LocalDate date) {
        int year = date.getYear();
        LocalDate prev = anniversaryInYear(baseAnniversary, year - 1);
        if (!date.isBefore(windowStart(prev)) && !date.isAfter(windowEnd(prev))) {
            return year - 1;
        }
        return year;
    }

    /** 计算日期所属周期的周年日。 */
    public static LocalDate cycleAnniversary(LocalDate baseAnniversary, LocalDate date) {
        return anniversaryInYear(baseAnniversary, cycleYear(baseAnniversary, date));
    }

    /** 闭区间判断：恰首日或末日均算在窗口内。 */
    public static boolean withinWindow(LocalDate date, LocalDate start, LocalDate end) {
        return !date.isBefore(start) && !date.isAfter(end);
    }

    /** 一般缺陷复查截止日：发现日 + 30 日（恰第 30 日提交仍受理）。 */
    public static LocalDate recheckDue(LocalDate foundDate) {
        return foundDate.plusDays(RECHECK_DAYS);
    }

    /**
     * 年度检验证书有效期截止：检验完成周期的“下一周期”窗口末日，
     * 再叠加本周期已批准展期月数。
     * 例如 2026 周期完成检验 → 截止 2027 周年日 +3 个月；展期 3 个月再顺延。
     */
    public static LocalDate annualCertValidUntil(LocalDate cycleAnniversary,
                                                 int extensionMonths) {
        return windowEnd(cycleAnniversary.plusYears(1)).plusMonths(extensionMonths);
    }
}
