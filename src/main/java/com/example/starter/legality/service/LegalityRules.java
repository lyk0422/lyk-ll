package com.example.starter.legality.service;

import com.example.starter.legality.domain.DutyRecord;
import com.example.starter.legality.domain.ViolationReasons;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 机组值勤合规规则引擎（纯函数，UTC 计时）。
 *
 * <p>规则（所有边界均为“恰满合法，超出违规”）：
 * <ul>
 *   <li>FDP 自报到起算，按报到时刻（UTC）时段分档：06:00-12:59→13 小时，
 *       13:00-21:59→12 小时，22:00-05:59→10 小时；</li>
 *   <li>任一日历日（UTC）累计延误延长不得超过 120 分钟；</li>
 *   <li>任一连续 7 个日历日（UTC）总执勤不得超过 60 小时；</li>
 *   <li>前一执勤实际结束后须连续休息满 10 小时方可再次报到。</li>
 * </ul>
 */
public final class LegalityRules {

    /** 报到 06:00-12:59 档 FDP 上限：13 小时。 */
    static final int FDP_LIMIT_MORNING = 13 * 60;

    /** 报到 13:00-21:59 档 FDP 上限：12 小时。 */
    static final int FDP_LIMIT_EVENING = 12 * 60;

    /** 报到 22:00-05:59 档 FDP 上限：10 小时。 */
    static final int FDP_LIMIT_NIGHT = 10 * 60;

    /** 单个 UTC 日历日累计延误延长上限：2 小时。 */
    static final int DAILY_DELAY_LIMIT_MINUTES = 120;

    /** 连续 7 个日历日总执勤上限：60 小时。 */
    static final int ROLLING_DUTY_LIMIT_MINUTES = 60 * 60;

    /** 连续休息期下限：10 小时。 */
    static final int REST_LIMIT_MINUTES = 10 * 60;

    private LegalityRules() {
    }

    /**
     * 按报到时刻（UTC）所在时段返回 FDP 上限分钟数：06:00-12:59→780，13:00-21:59→720，其余→600。
     *
     * @param reportUtc 报到时刻 UTC
     * @return FDP 上限分钟数
     */
    public static int fdpLimitMinutes(OffsetDateTime reportUtc) {
        int hour = reportUtc.getHour();
        if (hour >= 6 && hour <= 12) {
            return FDP_LIMIT_MORNING;
        }
        if (hour >= 13 && hour <= 21) {
            return FDP_LIMIT_EVENING;
        }
        return FDP_LIMIT_NIGHT;
    }

    /**
     * 评估单条值勤的违规原因码。
     *
     * @param reportUtc       报到时刻 UTC
     * @param scheduledEndUtc 计划结束时刻 UTC
     * @param delayMinutes    本条已受理延误延长分钟数
     * @param history         该机组成员报到时刻更早的既有值勤记录（按报到时刻升序）
     * @param priorDayDelay   本条报到 UTC 日历日上、除本条外既有延长分钟合计
     * @return 违规原因码列表（去重、稳定顺序），空列表表示合法
     */
    public static List<String> evaluate(OffsetDateTime reportUtc,
                                        OffsetDateTime scheduledEndUtc,
                                        int delayMinutes,
                                        List<DutyRecord> history,
                                        int priorDayDelay) {
        Set<String> reasons = new LinkedHashSet<>();

        OffsetDateTime actualEnd = scheduledEndUtc.plusMinutes(delayMinutes);

        // 规则一：FDP 上限按报到时段分档，恰达上限合法。
        long fdpMinutes = Duration.between(reportUtc, actualEnd).toMinutes();
        if (fdpMinutes > fdpLimitMinutes(reportUtc)) {
            reasons.add(ViolationReasons.M_FDP_LIMIT);
        }

        // 规则二：同一 UTC 日历日累计延误延长不得超过 120 分钟，恰满合法。
        if ((long) priorDayDelay + delayMinutes > DAILY_DELAY_LIMIT_MINUTES) {
            reasons.add(ViolationReasons.M_DAILY_DELAY_LIMIT);
        }

        // 规则三：以本条报到日为末日的连续 7 个日历日窗口总执勤不得超过 60 小时。
        // 只检查覆盖本条的最宽窗口即可：更早窗口在历史记录提交时已校验过。
        var windowStart = reportUtc.toLocalDate().minusDays(6);
        long windowMinutes = Duration.between(reportUtc, actualEnd).toMinutes();
        for (DutyRecord past : history) {
            var pastDate = past.reportTime().toLocalDate();
            if (!pastDate.isBefore(windowStart) && !pastDate.isAfter(reportUtc.toLocalDate())) {
                windowMinutes += Duration.between(past.reportTime(), past.actualEndTime()).toMinutes();
            }
        }
        if (windowMinutes > ROLLING_DUTY_LIMIT_MINUTES) {
            reasons.add(ViolationReasons.M_ROLLING_60H);
        }

        // 规则四：距报到时刻最近的前一执勤实际结束后须休息满 10 小时，恰满合法。
        DutyRecord previous = null;
        for (DutyRecord past : history) {
            if (past.reportTime().isBefore(reportUtc)
                    && (previous == null || past.reportTime().isAfter(previous.reportTime()))) {
                previous = past;
            }
        }
        if (previous != null) {
            long restMinutes = Duration.between(previous.actualEndTime(), reportUtc).toMinutes();
            if (restMinutes < REST_LIMIT_MINUTES) {
                reasons.add(ViolationReasons.M_REST_LIMIT);
            }
        }

        return new ArrayList<>(reasons);
    }
}
