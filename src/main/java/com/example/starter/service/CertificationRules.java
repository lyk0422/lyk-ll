package com.example.starter.service;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;

import com.example.starter.domain.SeedGrade;

/**
 * 认证分级领域规则（全部为纯函数，便于边界单测）：
 * <ul>
 *     <li>田间纯度门槛：原种 99.9%，良种 99.0%；</li>
 *     <li>室内发芽率门槛：原种 90%，良种 85%；</li>
 *     <li>恰好达标判合格（compareTo 的 &gt;= 比较，99.900 与 99.9 视为同值）；</li>
 *     <li>发证不得越级：两项均达原种发原种证，否则两项均达良种发良种证；</li>
 *     <li>复检窗口：首检日起 15 个自然日（UTC 日界），第 15 日当日受理。</li>
 * </ul>
 */
public final class CertificationRules {

    /** 原种田间纯度门槛（百分数，%）。 */
    public static final BigDecimal ORIGINAL_PURITY = new BigDecimal("99.9");
    /** 良种田间纯度门槛（百分数，%）。 */
    public static final BigDecimal IMPROVED_PURITY = new BigDecimal("99.0");
    /** 原种室内发芽率门槛（百分数，%）。 */
    public static final BigDecimal ORIGINAL_GERMINATION = new BigDecimal("90.0");
    /** 良种室内发芽率门槛（百分数，%）。 */
    public static final BigDecimal IMPROVED_GERMINATION = new BigDecimal("85.0");

    /** 复检受理期限（自然日，含第 15 日）。 */
    public static final int RETEST_WINDOW_DAYS = 15;

    private CertificationRules() {
    }

    /** 田间纯度是否达到指定等级门槛（恰好达标判合格）。 */
    public static boolean purityPasses(SeedGrade grade, BigDecimal purity) {
        BigDecimal threshold = grade == SeedGrade.ORIGINAL ? ORIGINAL_PURITY : IMPROVED_PURITY;
        return normalize(purity).compareTo(threshold) >= 0;
    }

    /** 发芽率是否达到指定等级门槛（恰好达标判合格）。 */
    public static boolean germinationPasses(SeedGrade grade, BigDecimal germination) {
        BigDecimal threshold = grade == SeedGrade.ORIGINAL
                ? ORIGINAL_GERMINATION : IMPROVED_GERMINATION;
        return normalize(germination).compareTo(threshold) >= 0;
    }

    /**
     * 分级发证判定：两项均达原种门槛 → 原种；两项均达良种门槛 → 良种；
     * 任一项低于良种门槛 → null（不得发证）。
     */
    public static SeedGrade gradeFor(BigDecimal purity, BigDecimal germination) {
        if (normalize(purity).compareTo(ORIGINAL_PURITY) >= 0
                && normalize(germination).compareTo(ORIGINAL_GERMINATION) >= 0) {
            return SeedGrade.ORIGINAL;
        }
        if (normalize(purity).compareTo(IMPROVED_PURITY) >= 0
                && normalize(germination).compareTo(IMPROVED_GERMINATION) >= 0) {
            return SeedGrade.IMPROVED;
        }
        return null;
    }

    /**
     * 复检受理截止日（UTC）：首检当日 +15 个自然日，截止日全天受理。
     * 例：首检 2026-09-01（任意时刻）→ 截止 2026-09-16（UTC 日界前）。
     */
    public static LocalDate retestDeadline(Instant firstInspectedAt) {
        return toDate(firstInspectedAt).plusDays(RETEST_WINDOW_DAYS);
    }

    /**
     * 指定时刻是否仍在复检受理窗口内（含第 15 日，跨日即逾期）。
     */
    public static boolean withinRetestWindow(Instant firstInspectedAt, Instant now) {
        LocalDate today = toDate(now);
        return !today.isAfter(retestDeadline(firstInspectedAt));
    }

    /** 两个自然日相差天数（deadline - base，UTC 日界）。 */
    public static long daysAfterFirstInspection(Instant firstInspectedAt, Instant now) {
        return java.time.Duration.between(toDate(firstInspectedAt).atStartOfDay(),
                toDate(now).atStartOfDay()).toDays();
    }

    private static LocalDate toDate(Instant instant) {
        return instant.atZone(ZoneOffset.UTC).toLocalDate();
    }

    private static BigDecimal normalize(BigDecimal value) {
        return value.stripTrailingZeros();
    }
}
