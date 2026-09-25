package com.example.starter.time;

import java.time.DayOfWeek;
import java.time.LocalDate;

/**
 * 工作日计算（合成规则）：周一至周五为工作日，周六周日不计入工作日。
 * 补正时限为告知后 5 个工作日，恰第 5 个工作日当日提交仍受理。
 */
public final class WorkdayCalculator {

    private WorkdayCalculator() {
    }

    /**
     * 从告知日（当日为第 0 日）起加 {@code workdays} 个工作日。
     * 例如告知日为周一，加 5 个工作日得到下周一（恰第 5 个工作日）。
     */
    public static LocalDate plusWorkdays(LocalDate noticeDate, int workdays) {
        LocalDate date = noticeDate;
        int remaining = workdays;
        while (remaining > 0) {
            date = date.plusDays(1);
            if (isWorkday(date)) {
                remaining--;
            }
        }
        return date;
    }

    /** 判断给定日期是否为工作日（含恰边界当日）。 */
    public static boolean isWorkday(LocalDate date) {
        DayOfWeek day = date.getDayOfWeek();
        return day != DayOfWeek.SATURDAY && day != DayOfWeek.SUNDAY;
    }
}
