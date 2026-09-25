package com.example.starter.cems;

/**
 * 污水排放在线监测（CEMS）领域常量：状态与动作取值。
 */
public final class CemsConstants {

    private CemsConstants() {
    }

    /** 排口状态：登记 → 监测中 → 已超标标记 → 限排中 →（复测解除回到监测中）→ 已归档(终态)。 */
    public static final String OUT_REGISTERED = "REGISTERED";
    public static final String OUT_MONITORING = "MONITORING";
    public static final String OUT_MARKED = "MARKED";
    public static final String OUT_RESTRICTED = "RESTRICTED";
    public static final String OUT_ARCHIVED = "ARCHIVED";

    /** 超标标记状态。 */
    public static final String MARK_ACTIVE = "ACTIVE";
    public static final String MARK_RESOLVED = "RESOLVED";

    /** 限排令状态：已下达 / 已超时升级 / 已关闭。 */
    public static final String ORDER_ISSUED = "ISSUED";
    public static final String ORDER_ESCALATED = "ESCALATED";
    public static final String ORDER_CLOSED = "CLOSED";

    /** 数据缺失督办状态。 */
    public static final String NOTICE_OPEN = "OPEN";
    public static final String NOTICE_CLEARED = "CLEARED";

    /** 时段类型。 */
    public static final String PERIOD_HOUR = "HOUR";
    public static final String PERIOD_DAY = "DAY";

    /** 幂等动作。 */
    public static final String ACT_REGISTER = "REGISTER";
    public static final String ACT_HOURLY_READING = "HOURLY_READING";
    public static final String ACT_DAILY_READING = "DAILY_READING";
    public static final String ACT_CONFIRM = "CONFIRM";
    public static final String ACT_RELEASE = "RELEASE";
    public static final String ACT_ARCHIVE = "ARCHIVE";

    /** 业务关键时长。 */
    public static final long CONFIRM_DEADLINE_HOURS = 2L;
    public static final long MISSING_TOLERANCE_HOURS = 2L;
    public static final int HOURLY_EXCEED_STREAK = 3;
}
