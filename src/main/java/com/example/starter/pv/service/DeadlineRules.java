package com.example.starter.pv.service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicInteger;

import com.example.starter.pv.domain.Seriousness;

/**
 * 时限规则（全部按 UTC 日界，可复算）：
 * <ul>
 *   <li>致死/危及生命：T0 起 7 日内，期限日 = T0 + 6 日；</li>
 *   <li>其他严重：T0 起 15 日内，期限日 = T0 + 14 日；</li>
 *   <li>恰在期限最后一日 23:59:59(UTC) 提交为及时，更晚为迟报；</li>
 *   <li>关闭须末次随访（无随访则以初报日为基准）后满 30 日，恰满 30 日可申请。</li>
 * </ul>
 */
public final class DeadlineRules {

    /** 致死/危及生命时限（含 T0 当日）。 */
    public static final int FATAL_DAYS = 7;
    /** 其他严重时限（含 T0 当日）。 */
    public static final int OTHER_SERIOUS_DAYS = 15;
    /** 关闭前无新信息观察期。 */
    public static final int CLOSE_GAP_DAYS = 30;

    private static final AtomicInteger CASE_SEQ = new AtomicInteger();
    private static final AtomicInteger BATCH_SEQ = new AtomicInteger();

    private DeadlineRules() {
    }

    /**
     * 计算初报期限日（UTC）。
     */
    public static LocalDate dueDate(Seriousness seriousness, LocalDate awarenessDate) {
        int span = seriousness == Seriousness.OTHER_SERIOUS ? OTHER_SERIOUS_DAYS : FATAL_DAYS;
        return awarenessDate.plusDays(span - 1L);
    }

    /**
     * 判定提交时刻是否及时：不晚于期限日 23:59:59(UTC)。
     */
    public static boolean isOnTime(LocalDate dueDate, Instant submittedAt) {
        LocalDateTime deadlineEnd = dueDate.atTime(23, 59, 59);
        Instant deadlineInstant = deadlineEnd.toInstant(ZoneOffset.UTC);
        return !submittedAt.isAfter(deadlineInstant);
    }

    /**
     * 关闭最早可申请日（UTC）：基准日 + 30，当日即可申请。
     */
    public static LocalDate closeEarliestDate(LocalDate baselineDate) {
        return baselineDate.plusDays(CLOSE_GAP_DAYS);
    }

    /**
     * 判定给定 UTC 日期是否已可申请关闭。
     */
    public static boolean isCloseable(LocalDate baselineDate, LocalDate todayUtc) {
        return !todayUtc.isBefore(closeEarliestDate(baselineDate));
    }

    /**
     * 生成个例编号：PV-C-yyyyMMddHHmmss-序列（UTC）。
     */
    public static String newCaseNo(Instant now) {
        return "PV-C-" + utcTimestamp(now) + "-" + String.format("%04d", CASE_SEQ.incrementAndGet());
    }

    /**
     * 生成递交批次号：PV-B-yyyyMMddHHmmss-序列（UTC）。
     */
    public static String newBatchNo(Instant now) {
        return "PV-B-" + utcTimestamp(now) + "-" + String.format("%04d", BATCH_SEQ.incrementAndGet());
    }

    private static String utcTimestamp(Instant now) {
        return java.time.format.DateTimeFormatter.ofPattern("yyyyMMddHHmmss")
                .withZone(ZoneOffset.UTC)
                .format(now);
    }
}
