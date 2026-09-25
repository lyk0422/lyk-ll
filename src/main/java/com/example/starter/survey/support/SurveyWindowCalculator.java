package com.example.starter.survey.support;

import com.example.starter.survey.domain.Ship;
import com.example.starter.survey.domain.SurveyType;

import java.time.DateTimeException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 检验窗口纯计算逻辑，全部按 UTC 日期（日界）运算：
 * <ul>
 *   <li>年度检验：每年周年日前后各 3 个月，窗口首日/末日完成均有效（闭区间）；</li>
 *   <li>中间检验：以交付日起每个 5 年特别检验周期内第 2、第 3 个周年日为锚点；</li>
 *   <li>特别检验：交付日起每满 5 年的周年日为锚点；</li>
 *   <li>展期：月度加法，恰满 3 个月合法；</li>
 *   <li>一般缺陷复查期限：发现日 +30 日，含当日。</li>
 * </ul>
 * 窗口台账可由 {@link Ship} 基准日按本类规则完整复算。
 */
public final class SurveyWindowCalculator {

    /** 窗口半宽月数（前后各 3 个月）。 */
    public static final int WINDOW_HALF_MONTHS = 3;
    /** 特别检验周期年数。 */
    public static final int SPECIAL_CYCLE_YEARS = 5;
    /** 一般缺陷复查期限天数。 */
    public static final int MINOR_RECTIFY_DAYS = 30;
    /** 展期最长月数。 */
    public static final int MAX_EXTENSION_MONTHS = 3;

    private SurveyWindowCalculator() {
    }

    /** 某个检验窗口（锚点与闭区间首尾，UTC）。 */
    public record SurveyWindow(SurveyType type, LocalDate anchor, LocalDate start, LocalDate end) {

        /** 指定日期（含首尾）是否落在窗口内。 */
        public boolean contains(LocalDate date) {
            return !date.isBefore(start) && !date.isAfter(end);
        }
    }

    /** 将基准月日照闰年调整到目标年份（2 月 29 日在平年落为 2 月 28 日）。 */
    public static LocalDate anniversaryInYear(LocalDate base, int year) {
        try {
            return LocalDate.of(year, base.getMonthValue(), base.getDayOfMonth());
        } catch (DateTimeException e) {
            return LocalDate.of(year, base.getMonthValue(), 28);
        }
    }

    /** 年度检验某一年的锚点（周年基准日的该年同月同日）。 */
    public static LocalDate annualAnchor(Ship ship, int year) {
        return anniversaryInYear(ship.annualAnniversary(), year);
    }

    /** 由锚点构造前后各 3 个月的闭区间窗口。 */
    public static SurveyWindow windowAround(SurveyType type, LocalDate anchor) {
        return new SurveyWindow(type, anchor,
                anchor.minusMonths(WINDOW_HALF_MONTHS),
                anchor.plusMonths(WINDOW_HALF_MONTHS));
    }

    /**
     * 找出指定日期（通常为今天）所属的检验窗口。
     *
     * @return 覆盖该日期的窗口；不落在任何窗口内时返回 empty（申请/登轮应据此拒绝）
     */
    public static Optional<SurveyWindow> windowContaining(Ship ship, SurveyType type, LocalDate date) {
        return candidateWindows(ship, type, date).stream()
                .filter(w -> w.contains(date))
                .findFirst();
    }

    /**
     * 复算指定日期邻近年的候选窗口（用于窗口命中判定与台账重建）。
     */
    public static List<SurveyWindow> candidateWindows(Ship ship, SurveyType type, LocalDate around) {
        List<SurveyWindow> windows = new ArrayList<>();
        switch (type) {
            case ANNUAL -> {
                int year = around.getYear();
                for (int y = year - 1; y <= year + 1; y++) {
                    windows.add(windowAround(SurveyType.ANNUAL, annualAnchor(ship, y)));
                }
            }
            case INTERMEDIATE, SPECIAL -> {
                int baseK = Math.floorDiv(
                        around.getYear() - ship.deliveryDate().getYear(), SPECIAL_CYCLE_YEARS);
                for (int k = baseK - 1; k <= baseK + 1; k++) {
                    LocalDate cycleStart = ship.deliveryDate().plusYears((long) k * SPECIAL_CYCLE_YEARS);
                    if (type == SurveyType.SPECIAL) {
                        windows.add(windowAround(SurveyType.SPECIAL, cycleStart));
                    } else {
                        windows.add(windowAround(SurveyType.INTERMEDIATE,
                                anniversaryInYear(ship.deliveryDate(),
                                        cycleStart.getYear() + 2)));
                        windows.add(windowAround(SurveyType.INTERMEDIATE,
                                anniversaryInYear(ship.deliveryDate(),
                                        cycleStart.getYear() + 3)));
                    }
                }
            }
        }
        return windows;
    }

    /** 一般缺陷复查截止日（发现日 + 30 日，含当日）。 */
    public static LocalDate minorDeadline(LocalDate foundDate) {
        return foundDate.plusDays(MINOR_RECTIFY_DAYS);
    }

    /** 展期后的日期（月度加法，恰满 3 个月合法）。 */
    public static LocalDate extend(LocalDate date, int months) {
        return date.plusMonths(months);
    }
}
