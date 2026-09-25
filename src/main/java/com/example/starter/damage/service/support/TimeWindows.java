package com.example.starter.damage.service.support;

/** UTC 自然日时间窗口工具。 */
public final class TimeWindows {

    /** 一天的毫秒数。 */
    public static final long DAY_MILLIS = 86_400_000L;

    private TimeWindows() {
    }

    /**
     * 批复有效期截止（排他，UTC 日界）。按自然日期间惯例，开始的批复当日不计入，
     * 次日为第 1 个自然日；批复日 +7 为第 7 个自然日（该日任意时刻完工有效），
     * 批复日 +8 的 00:00:00 UTC 起逾期。
     */
    public static long approvalValidUntilUtc(long approvedAtUtc) {
        long dayStart = Math.floorDiv(approvedAtUtc, DAY_MILLIS) * DAY_MILLIS;
        return dayStart + 8L * DAY_MILLIS;
    }

    /** 完工时刻是否落在批复有效期内（恰第 7 个自然日有效）。 */
    public static boolean withinApprovalWindow(long completedAtUtc, long validUntilUtc) {
        return completedAtUtc < validUntilUtc;
    }
}
