package com.example.starter.margin.support;

import com.example.starter.margin.AbstractMarginH2Test;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZonedDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 追保截止时间 = 次一交易日 15:00（UTC+8）：周末顺延，遇 trade_holiday 合成休市日继续顺延。
 */
class TradingCalendarTest extends AbstractMarginH2Test {

    @Autowired
    private TradingCalendar calendar;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void deadlineSkipsWeekendToNextMonday15Beijing() {
        // 2026-09-25 为周五：次一交易日为周一 2026-09-28 15:00（UTC+8）
        Instant friday = ZonedDateTime.of(2026, 9, 25, 10, 0, 0, 0, TradingCalendar.ZONE).toInstant();
        Instant deadline = calendar.nextTradingDayDeadline(friday);
        ZonedDateTime bj = deadline.atZone(TradingCalendar.ZONE);
        assertThat(bj.toLocalDate()).isEqualTo(LocalDate.of(2026, 9, 28));
        assertThat(bj.getHour()).isEqualTo(15);

        // 周五夜间（UTC 已到周六，但北京仍为周五 23:00）仍以北京日期为准 → 周一
        Instant fridayNightBj = ZonedDateTime.of(2026, 9, 25, 23, 0, 0, 0, TradingCalendar.ZONE).toInstant();
        assertThat(calendar.nextTradingDayDeadline(fridayNightBj).atZone(TradingCalendar.ZONE).toLocalDate())
                .isEqualTo(LocalDate.of(2026, 9, 28));
    }

    @Test
    void deadlineSkipsConfiguredHoliday() {
        // 周一设为休市日：次一交易日顺延到周二
        jdbcTemplate.update("INSERT INTO trade_holiday (holiday_date) VALUES (?)",
                java.sql.Date.valueOf(LocalDate.of(2026, 9, 28)));
        Instant friday = ZonedDateTime.of(2026, 9, 25, 10, 0, 0, 0, TradingCalendar.ZONE).toInstant();
        Instant deadline = calendar.nextTradingDayDeadline(friday);
        assertThat(deadline.atZone(TradingCalendar.ZONE).toLocalDate())
                .isEqualTo(LocalDate.of(2026, 9, 29));
        assertThat(deadline.atZone(TradingCalendar.ZONE).getHour()).isEqualTo(15);
    }
}
