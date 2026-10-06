package com.example.starter.service;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.example.starter.domain.Interval;

/**
 * 引航员工时校验：
 * <ul>
 *   <li>连续作业达 4 小时（恰满 4 小时即判定）须强制休息 1 小时；休息不足 1 小时视为连续作业的一部分，
 *       因此同一段连续作业（相邻作业间隔均小于 1 小时）累计工时不得超过 4 小时。</li>
 *   <li>单日（自然日）累计引航不超过 10 小时，恰 10 小时合法。</li>
 * </ul>
 */
@Component
public class WorkHourValidator {

    /** 连续作业上限：恰满 4 小时即触发强制休息判定。 */
    public static final Duration MAX_CONTINUOUS_WORK = Duration.ofHours(4);

    /** 强制休息时长：达到上限后须休息 1 小时方可继续作业。 */
    public static final Duration REQUIRED_REST = Duration.ofHours(1);

    /** 单日累计引航上限：恰 10 小时合法。 */
    public static final Duration MAX_DAILY_WORK = Duration.ofHours(10);

    /**
     * 校验在既有作业区间基础上加入候选区间后是否仍满足工时规则。
     *
     * @param existing  引航员既有生效中的作业区间（可为空）
     * @param candidate 候选作业区间
     * @return 违规原因列表，空列表表示合法
     */
    public List<String> validate(List<Interval> existing, Interval candidate) {
        List<Interval> all = new ArrayList<>(existing);
        all.add(candidate);
        all.sort(Comparator.comparing(Interval::start));

        List<String> violations = new ArrayList<>();
        checkContinuousWork(all, violations);
        checkDailyWork(all, violations);
        return violations;
    }

    /**
     * 连续作业检查：相邻区间间隔小于 1 小时视为同一段连续作业；
     * 每段累计工时不得超过 4 小时（恰 4 小时合法，其后须由不小于 1 小时的间隔断开）。
     */
    private void checkContinuousWork(List<Interval> sorted, List<String> violations) {
        Duration stretchWork = Duration.ZERO;
        LocalDateTime stretchStart = null;
        LocalDateTime previousEnd = null;
        for (Interval interval : sorted) {
            if (previousEnd == null
                    || Duration.between(previousEnd, interval.start()).compareTo(REQUIRED_REST) >= 0) {
                stretchWork = interval.duration();
                stretchStart = interval.start();
            } else {
                stretchWork = stretchWork.plus(interval.duration());
            }
            if (stretchWork.compareTo(MAX_CONTINUOUS_WORK) > 0) {
                violations.add("引航员自 " + stretchStart + " 起连续作业超过 4 小时且未休息满 1 小时");
                return;
            }
            previousEnd = interval.end();
        }
    }

    /**
     * 单日累计检查：跨天区间按自然日切分后逐日累计，任一自然日超过 10 小时即违规（恰 10 小时合法）。
     */
    private void checkDailyWork(List<Interval> sorted, List<String> violations) {
        Map<LocalDate, Duration> daily = new HashMap<>();
        for (Interval interval : sorted) {
            LocalDateTime cursor = interval.start();
            while (cursor.isBefore(interval.end())) {
                LocalDate day = cursor.toLocalDate();
                LocalDateTime dayEnd = day.plusDays(1).atStartOfDay();
                LocalDateTime segmentEnd = interval.end().isBefore(dayEnd) ? interval.end() : dayEnd;
                daily.merge(day, Duration.between(cursor, segmentEnd), Duration::plus);
                cursor = segmentEnd;
            }
        }
        for (Map.Entry<LocalDate, Duration> entry : daily.entrySet()) {
            if (entry.getValue().compareTo(MAX_DAILY_WORK) > 0) {
                violations.add("引航员 " + entry.getKey() + " 单日累计引航超过 10 小时");
            }
        }
    }
}
