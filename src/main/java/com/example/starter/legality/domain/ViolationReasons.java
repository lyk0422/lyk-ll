package com.example.starter.legality.domain;

/**
 * 违规原因码。成员级原因码带 {@code M_} 前缀，便于与批次级提示区分。
 */
public final class ViolationReasons {

    /** 飞行执勤期超出报到时段上限（13/12/10 小时分档）。 */
    public static final String M_FDP_LIMIT = "M_FDP_LIMIT";

    /** 任一日历日（UTC）累计延误延长超过 120 分钟。 */
    public static final String M_DAILY_DELAY_LIMIT = "M_DAILY_DELAY_LIMIT";

    /** 任一连续 7 个日历日（UTC）总执勤超过 60 小时。 */
    public static final String M_ROLLING_60H = "M_ROLLING_60H";

    /** 距前一执勤结束不足连续 10 小时休息期即再次报到。 */
    public static final String M_REST_LIMIT = "M_REST_LIMIT";

    private ViolationReasons() {
    }
}
