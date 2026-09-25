package com.example.starter.feed.service;

import com.example.starter.feed.domain.BatchMedication;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

/**
 * 休药期计算（纯逻辑，UTC 日界，可独立复算）。
 * <p>口径（题干唯一口径）：
 * <ol>
 *   <li>同一批次多配方合并：取各药物中的<strong>最大休药天数</strong> N 与<strong>最晚末次饲喂时刻</strong> T；</li>
 *   <li>T 所在 UTC 日为 D，恰满休药天数当日（D+N）24:00 后方可出栏，
 *       即合法时刻为 (D+N+1) 00:00:00 UTC（含该时刻）；</li>
 *   <li>该合法时刻所在 UTC 日 D+N+1 即“最早合法出栏日”。</li>
 * </ol>
 */
public final class WithdrawalCalculator {

    private WithdrawalCalculator() {
    }

    /**
     * 合并后的休药期窗口。
     *
     * @param latestFeedingTime  最晚末次含药饲喂时刻（UTC）
     * @param maxWithdrawalDays  各药物最大休药天数（天）
     * @param earliestSlaughterDate 最早合法出栏日（UTC），该日 00:00 起合法
     * @param eligibleInstant    最早合法出栏时刻（UTC，含）
     */
    public record Window(Instant latestFeedingTime, int maxWithdrawalDays,
                         LocalDate earliestSlaughterDate, Instant eligibleInstant) {
    }

    /** 取最晚末次饲喂时刻；无义务行时返回 null。 */
    public static Instant latestFeedingTime(List<BatchMedication> medications) {
        Instant latest = null;
        for (BatchMedication m : medications) {
            if (latest == null || m.lastFeedingTime().isAfter(latest)) {
                latest = m.lastFeedingTime();
            }
        }
        return latest;
    }

    /** 取各义务行中的最大休药天数；无义务行返回 0。 */
    public static int maxWithdrawalDays(List<BatchMedication> medications) {
        return medications.stream().mapToInt(BatchMedication::maxWithdrawalDays).max().orElse(0);
    }

    /**
     * 按唯一合并口径计算休药期窗口。
     *
     * @param medications 批次全部有效（非迟到）含药义务行
     */
    public static Window calculate(List<BatchMedication> medications) {
        if (medications.isEmpty()) {
            throw new IllegalArgumentException("批次无含药义务行，不构成休药期窗口");
        }
        Instant latest = latestFeedingTime(medications);
        int maxDays = maxWithdrawalDays(medications);
        LocalDate feedDay = LocalDate.ofInstant(latest, ZoneOffset.UTC);
        LocalDate eligibleDate = feedDay.plusDays((long) maxDays + 1);
        Instant eligibleInstant = LocalDateTime.of(eligibleDate, java.time.LocalTime.MIN)
                .toInstant(ZoneOffset.UTC);
        return new Window(latest, maxDays, eligibleDate, eligibleInstant);
    }

    /**
     * 出栏判定：申请时刻不早于合法时刻即为通过；恰等于合法时刻（恰满当日 24:00）合法。
     */
    public static boolean canSlaughter(Window window, Instant applyTime) {
        return !applyTime.isBefore(window.eligibleInstant());
    }
}
