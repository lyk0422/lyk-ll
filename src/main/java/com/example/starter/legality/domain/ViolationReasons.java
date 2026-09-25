package com.example.starter.legality.domain;

/**
 * 违规原因代码。
 */
public final class ViolationReasons {

    /** FDP（含延误延长）超出报到时段分档上限 */
    public static final String FDP_LIMIT_EXCEEDED = "FDP_LIMIT_EXCEEDED";
    /** 任一 UTC 日历日累计延误延长超过 2 小时 */
    public static final String DAILY_EXTENSION_EXCEEDED = "DAILY_EXTENSION_EXCEEDED";
    /** 任一连续 7 个 UTC 日历日总执勤超过 60 小时 */
    public static final String WEEKLY_DUTY_EXCEEDED = "WEEKLY_DUTY_EXCEEDED";
    /** 距上一执勤结束不足连续 10 小时休息期 */
    public static final String REST_PERIOD_SHORT = "REST_PERIOD_SHORT";

    private ViolationReasons() {
    }
}
