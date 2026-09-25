package com.example.starter.margin.support;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;

/**
 * 交易日历：周末与 trade_holiday 表中的合成休市日休市。
 * 追保截止时间 = 通知后次一交易日 15:00（Asia/Shanghai, UTC+8）。
 */
@Component
public class TradingCalendar {

    /** 追保截止时刻所在时区：UTC+8 */
    public static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    private final JdbcTemplate jdbcTemplate;

    public TradingCalendar(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** 通知生成于 now，返回次一交易日 15:00（UTC+8）对应的 UTC 时刻。 */
    public Instant nextTradingDayDeadline(Instant now) {
        LocalDate today = now.atZone(ZONE).toLocalDate();
        LocalDate next = today.plusDays(1);
        int guard = 0;
        while (!isTradingDay(next) && guard++ < 30) {
            next = next.plusDays(1);
        }
        ZonedDateTime deadline = next.atTime(15, 0).atZone(ZONE);
        return deadline.toInstant();
    }

    /** 指定日期是否为交易日：非周末且不在休市表。 */
    public boolean isTradingDay(LocalDate date) {
        DayOfWeek dow = date.getDayOfWeek();
        if (dow == DayOfWeek.SATURDAY || dow == DayOfWeek.SUNDAY) {
            return false;
        }
        Integer cnt = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM trade_holiday WHERE holiday_date = ?",
                Integer.class, java.sql.Date.valueOf(date));
        return cnt == null || cnt == 0;
    }
}
