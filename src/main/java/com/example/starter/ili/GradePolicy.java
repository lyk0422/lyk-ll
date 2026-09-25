package com.example.starter.ili;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

/**
 * 缺陷分级策略：按深度年化增长率（百分点/年）分级。
 * 增长率 &lt; 5%/年为稳定，&ge; 5%/年为增长，&ge; 15%/年为严重。
 */
public final class GradePolicy {

    /** 增长分级阈值（百分点/年），达到或超过即为增长。 */
    public static final BigDecimal GROWING_THRESHOLD = new BigDecimal("5");

    /** 严重分级阈值（百分点/年），达到或超过即为严重。 */
    public static final BigDecimal SEVERE_THRESHOLD = new BigDecimal("15");

    /** 一年按 365 天折算年化增长率。 */
    public static final BigDecimal DAYS_PER_YEAR = new BigDecimal("365");

    private GradePolicy() {
    }

    /** 分级结果。 */
    public enum Grade {
        STABLE, GROWING, SEVERE
    }

    /**
     * 计算深度年化增长率（百分点/年），保留 4 位小数（HALF_UP）。
     *
     * @param oldDepthPct 旧回合深度（壁厚百分比）
     * @param newDepthPct 新回合深度（壁厚百分比）
     * @param oldDate     旧回合检测日期
     * @param newDate     新回合检测日期，必须晚于旧回合
     */
    public static BigDecimal annualGrowthRate(BigDecimal oldDepthPct, BigDecimal newDepthPct,
            LocalDate oldDate, LocalDate newDate) {
        long days = ChronoUnit.DAYS.between(oldDate, newDate);
        if (days <= 0) {
            throw new IllegalArgumentException("新回合检测日期必须晚于旧回合检测日期");
        }
        BigDecimal depthDelta = newDepthPct.subtract(oldDepthPct);
        return depthDelta.multiply(DAYS_PER_YEAR)
                .divide(BigDecimal.valueOf(days), 4, RoundingMode.HALF_UP);
    }

    /**
     * 按年化增长率分级：&lt;5 稳定，&ge;5 增长，&ge;15 严重。
     */
    public static Grade grade(BigDecimal annualRatePctPerYear) {
        if (annualRatePctPerYear.compareTo(SEVERE_THRESHOLD) >= 0) {
            return Grade.SEVERE;
        }
        if (annualRatePctPerYear.compareTo(GROWING_THRESHOLD) >= 0) {
            return Grade.GROWING;
        }
        return Grade.STABLE;
    }
}
