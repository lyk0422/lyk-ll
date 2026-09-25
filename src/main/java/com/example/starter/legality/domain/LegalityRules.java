package com.example.starter.legality.domain;

import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;

/**
 * FDP 合规规则：报到时段分档、每日延长上限、7 日工时上限与最短休息期。
 * 时刻均以 UTC 存储；报到分档按起飞机场本地时区。
 */
@Component
public class LegalityRules {

    /** 每日历日（UTC）累计延长上限：2 小时（含边界，恰满合法） */
    public static final int DAILY_EXTENSION_LIMIT_MINUTES = 120;
    /** 连续 7 个日历日总执勤上限：60 小时（含边界，恰 60 合法） */
    public static final int WEEKLY_DUTY_LIMIT_MINUTES = 60 * 60;
    /** 前一执勤结束后的最短连续休息期：10 小时（恰满即可再次报到） */
    public static final int MIN_REST_MINUTES = 600;

    private final Clock clock;

    public LegalityRules(Clock clock) {
        this.clock = clock;
    }

    /**
     * 按报到时刻在起飞机场本地时区的钟面时段返回 FDP 上限（分钟）：
     * 06:00-12:59 为 13 小时；13:00-21:59 为 12 小时；22:00-05:59 为 10 小时。
     * 边界归属：06:00 落入 13h 档；13:00 落入 12h 档；22:00 落入 10h 档。
     */
    public int tierLimitMinutes(Instant reportTimeUtc, ZoneId departureZone) {
        LocalTime localReport = reportTimeUtc.atZone(departureZone).toLocalTime();
        int hour = localReport.getHour();
        if (hour >= 6 && hour <= 12) {
            return 13 * 60;
        }
        if (hour >= 13 && hour <= 21) {
            return 12 * 60;
        }
        return 10 * 60;
    }

    /** 当前时刻（UTC），测试可替换固定时钟。 */
    public Instant now() {
        return Instant.now(clock);
    }
}
