package com.example.starter.feed.service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Map;

/**
 * 休药期纯计算工具（UTC 日界口径），无状态、可对任意输入复算。
 *
 * <p>口径（唯一）：</p>
 * <ul>
 *   <li>末次饲喂含药饲料时刻 {@code t}（UTC）所在 UTC 日历日 D；</li>
 *   <li>休药期届满日 = D + N 天（N = 各药物最大休药天数）；</li>
 *   <li>恰满休药天数当日 24:00（即届满日次日 00:00 UTC）之后方可出栏；</li>
 *   <li>判断出栏申请时刻 {@code a} 是否合法：{@code a >= earliestSlaughter}。</li>
 * </ul>
 */
public final class WithdrawalCalculator {

    private WithdrawalCalculator() {
    }

    /**
     * 取某时刻的 UTC 日历日。
     */
    public static LocalDate utcDate(Instant instant) {
        return instant.atZone(ZoneOffset.UTC).toLocalDate();
    }

    /**
     * 计算休药期届满日（UTC）：末次饲喂日 + 休药天数。
     */
    public static LocalDate withdrawalUntilDate(Instant lastFeedAtUtc, int withdrawalDays) {
        if (withdrawalDays < 0) {
            throw new IllegalArgumentException("休药天数不能为负数");
        }
        return utcDate(lastFeedAtUtc).plusDays(withdrawalDays);
    }

    /**
     * 最早合法出栏时刻：届满日次日 00:00 UTC（即届满日当日 24:00）。
     */
    public static Instant earliestSlaughter(LocalDate withdrawalUntilDate) {
        return withdrawalUntilDate.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();
    }

    /**
     * 合并多个含药配方/饲喂事件：取最晚末次饲喂时刻与各药物最大休药天数。
     *
     * @param contributions 每项为 {lastFeedAtUtc: Instant, maxWithdrawalDays: int}
     * @return 合并后的最晚饲喂时刻、决定性最大休药天数及对应届满日；无贡献项返回 null
     */
    @SafeVarargs
    public static MergedWithdrawal merge(Map.Entry<Instant, Integer>... contributions) {
        Instant latestFeed = null;
        int maxDays = 0;
        for (Map.Entry<Instant, Integer> e : contributions) {
            if (e.getKey() == null) {
                continue;
            }
            if (latestFeed == null || e.getKey().isAfter(latestFeed)) {
                latestFeed = e.getKey();
            }
            maxDays = Math.max(maxDays, e.getValue());
        }
        if (latestFeed == null) {
            return null;
        }
        LocalDate until = withdrawalUntilDate(latestFeed, maxDays);
        return new MergedWithdrawal(latestFeed, maxDays, until, earliestSlaughter(until));
    }

    /**
     * 合并结果。
     *
     * @param latestFeedAtUtc       最晚末次含药饲喂时刻
     * @param maxWithdrawalDays     各药物最大休药天数
     * @param withdrawalUntilDate   休药期届满日（UTC 日历日）
     * @param earliestSlaughterUtc  最早合法出栏时刻（UTC）
     */
    public record MergedWithdrawal(
            Instant latestFeedAtUtc,
            int maxWithdrawalDays,
            LocalDate withdrawalUntilDate,
            Instant earliestSlaughterUtc
    ) {
    }
}
