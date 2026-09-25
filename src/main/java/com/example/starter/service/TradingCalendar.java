package com.example.starter.service;

import org.springframework.stereotype.Component;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

/**
 * 交易日历（合成）：周一至周五为交易日，不考虑节假日。
 * 追保补足截止时间为通知后次一交易日 15:00（UTC+8）。
 */
@Component
public class TradingCalendar {

    /** 追保补足截止时刻。 */
    public static final LocalTime DEADLINE_TIME = LocalTime.of(15, 0);

    public boolean isTradingDay(LocalDate date) {
        DayOfWeek dow = date.getDayOfWeek();
        return dow != DayOfWeek.SATURDAY && dow != DayOfWeek.SUNDAY;
    }

    /** 触发日期的次一交易日 15:00。 */
    public LocalDateTime nextTradingDayDeadline(LocalDateTime triggerAt) {
        LocalDate date = triggerAt.toLocalDate().plusDays(1);
        while (!isTradingDay(date)) {
            date = date.plusDays(1);
        }
        return LocalDateTime.of(date, DEADLINE_TIME);
    }
}
