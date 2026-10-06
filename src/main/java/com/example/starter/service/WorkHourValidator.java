package com.example.starter.service;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * 引航员工时校验（纯函数，便于单元测试）：
 * <ul>
 *   <li>连续作业满 4 小时（恰满 4 小时即判定）后，须强制休息 1 小时才允许开始下一条计划；
 *       相邻计划间隔不足 1 小时视为连续作业。</li>
 *   <li>单日累计引航不超过 10 小时，恰 10 小时合法；计划按开始时刻归属自然日。</li>
 * </ul>
 */
public final class WorkHourValidator {

    /** 连续作业上限：分钟。恰满即触发强制休息。 */
    public static final long CONTINUOUS_LIMIT_MINUTES = 4 * 60;
    /** 强制休息时长：分钟。间隔不足该值视为连续作业。 */
    public static final long REQUIRED_REST_MINUTES = 60;
    /** 单日累计上限：分钟。恰满合法。 */
    public static final long DAILY_LIMIT_MINUTES = 10 * 60;

    /** 计划占用区间（半开区间 [start, end)）。 */
    public record Interval(LocalDateTime start, LocalDateTime end) {
        public long durationMinutes() {
            return Duration.between(start, end).toMinutes();
        }
    }

    private WorkHourValidator() {
    }

    /**
     * 校验在既有计划基础上新增 [newStart, newEnd) 是否违反工时规则。
     *
     * @param existing 引航员既有有效计划区间（不含被改期/替换的计划本身）
     * @param newPlan  新增或改期后的计划区间
     * @return 违规则返回错误信息，否则为空
     */
    public static Optional<String> validate(List<Interval> existing, Interval newPlan) {
        List<Interval> all = new ArrayList<>(existing);
        all.add(newPlan);
        all.sort(Comparator.comparing(Interval::start));

        Optional<String> dailyViolation = checkDaily(all, newPlan);
        if (dailyViolation.isPresent()) {
            return dailyViolation;
        }
        return checkContinuousRest(all, all.indexOf(newPlan));
    }

    /** 单日累计：新计划所在自然日的累计工时须 ≤ 10 小时（恰 10 小时合法）。 */
    private static Optional<String> checkDaily(List<Interval> all, Interval newPlan) {
        LocalDate day = newPlan.start().toLocalDate();
        long total = all.stream()
                .filter(i -> i.start().toLocalDate().equals(day))
                .mapToLong(Interval::durationMinutes)
                .sum();
        if (total > DAILY_LIMIT_MINUTES) {
            return Optional.of("引航员单日累计引航 " + total + " 分钟，超过 10 小时上限");
        }
        return Optional.empty();
    }

    /**
     * 连续作业与强制休息：自新计划起向后检查，每条计划开始前的连续作业块
     * （间隔不足 1 小时即串联）达到 4 小时（恰满即判定）则违规。
     */
    private static Optional<String> checkContinuousRest(List<Interval> sorted, int fromIndex) {
        for (int i = Math.max(fromIndex, 0); i < sorted.size(); i++) {
            long continuousBefore = continuousWorkBefore(sorted, i);
            if (continuousBefore >= CONTINUOUS_LIMIT_MINUTES) {
                return Optional.of("引航员连续作业已达 " + continuousBefore
                        + " 分钟（满 4 小时），须强制休息 1 小时");
            }
        }
        return Optional.empty();
    }

    /**
     * 计算第 index 条计划开始之前的连续作业块总工时：
     * 向前串联所有间隔不足 1 小时的相邻计划，累计其作业时长。
     */
    private static long continuousWorkBefore(List<Interval> sorted, int index) {
        long total = 0;
        LocalDateTime nextStart = sorted.get(index).start();
        for (int i = index - 1; i >= 0; i--) {
            Interval prev = sorted.get(i);
            long gap = Duration.between(prev.end(), nextStart).toMinutes();
            if (gap >= REQUIRED_REST_MINUTES || gap < 0) {
                // 已休息满 1 小时（或区间异常重叠），连续作业中断
                break;
            }
            total += prev.durationMinutes();
            nextStart = prev.start();
        }
        return total;
    }
}
