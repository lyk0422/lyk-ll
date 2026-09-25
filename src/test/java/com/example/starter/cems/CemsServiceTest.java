package com.example.starter.cems;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import com.example.starter.cems.CemsTypes.ArchiveOutletRequest;
import com.example.starter.cems.CemsTypes.ArchiveResult;
import com.example.starter.cems.CemsTypes.ConfirmOrderRequest;
import com.example.starter.cems.CemsTypes.DailyEvalRequest;
import com.example.starter.cems.CemsTypes.DailyEvalResult;
import com.example.starter.cems.CemsTypes.GapCheckRequest;
import com.example.starter.cems.CemsTypes.GapResult;
import com.example.starter.cems.CemsTypes.HourlyReadingRequest;
import com.example.starter.cems.CemsTypes.HourlyReadingResult;
import com.example.starter.cems.CemsTypes.OrderView;
import com.example.starter.cems.CemsTypes.OutletView;
import com.example.starter.cems.CemsTypes.Receipt;
import com.example.starter.cems.CemsTypes.RecomputeRequest;
import com.example.starter.cems.CemsTypes.RecomputeResult;
import com.example.starter.cems.CemsTypes.ReleaseRestrictionRequest;
import com.example.starter.cems.CemsTypes.ReleaseResult;

/**
 * 主流程与失败分支业务测试：恰等限值、恰连续 3 次限排、恰 2 小时缺失、恰满 2 小时升级、
 * 复测解除、幂等重放/异参 409、归档终态与迟到事件、复算。数据落在真实 H2（MODE=MySQL）。
 */
@SpringBootTest
class CemsServiceTest {

    private static final String CODE = "OUT-01";
    private static final LocalDate DAY = LocalDate.of(2026, 9, 25);

    @Autowired
    private CemsService service;
    @Autowired
    private CemsClock clock;
    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        CemsTestSupport.clean(jdbc);
        clock.reset();
        clock.setFixed(CemsTestSupport.T0);
        CemsTestSupport.register(service, CODE, "50", "10");
    }

    private HourlyReadingResult hour(int h, String value) {
        return service.reportHourly(new HourlyReadingRequest(
                CODE, LocalDateTime.of(2026, 9, 26, h, 0), new BigDecimal(value)));
    }

    private long count(String table, String where, Object... args) {
        Long c = jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE outlet_code = ?" + where,
                Long.class, mergeArgs(CODE, args));
        return c == null ? 0 : c;
    }

    private Object[] mergeArgs(String code, Object[] args) {
        Object[] all = new Object[args.length + 1];
        all[0] = code;
        System.arraycopy(args, 0, all, 1, args.length);
        return all;
    }

    @Test
    void dailyAverageEqualLimitIsCompliant() {
        DailyEvalResult equal = service.evaluateDaily(
                new DailyEvalRequest("d-equal", CODE, DAY, new BigDecimal("50.0")));
        assertFalse(equal.exceeded(), "日均值恰等于许可限值应判达标");
        assertNull(equal.markId());
        assertEquals(0, count("exceedance_mark", " AND mark_type='DAILY_AVG'"));

        DailyEvalResult over = service.evaluateDaily(
                new DailyEvalRequest("d-over", CODE, DAY.plusDays(1), new BigDecimal("50.01")));
        assertTrue(over.exceeded());
        assertNotNull(over.markId());
    }

    @Test
    void hourlyEqualLimitDoesNotStartChain() {
        HourlyReadingResult r = hour(8, "10.00");
        assertFalse(r.exceeded());
        assertEquals(0, r.chainIndex());
    }

    @Test
    void exactlyThreeConsecutiveHourlyExceedancesTriggerOrder() {
        HourlyReadingResult first = hour(8, "11");
        HourlyReadingResult second = hour(9, "12");
        HourlyReadingResult third = hour(10, "13");

        assertEquals(1, first.chainIndex());
        assertFalse(first.restrictionTriggered());
        assertEquals(2, second.chainIndex());
        assertFalse(second.restrictionTriggered());
        assertEquals(3, third.chainIndex(), "恰连续第 3 次小时超标");
        assertTrue(third.restrictionTriggered(), "恰连续 3 次应自动下达限排令");
        assertEquals("PENDING_CONFIRM", third.orderStatus());
        assertEquals("RESTRICTED", service.getOutlet(CODE).status());
        assertEquals(1, count("restriction_order", ""));
    }

    @Test
    void onlyTwoConsecutiveExceedancesDoNotTriggerAndCompliantHourResetsChain() {
        assertEquals(1, hour(8, "11").chainIndex());
        HourlyReadingResult second = hour(9, "12");
        assertEquals(2, second.chainIndex());
        assertFalse(second.restrictionTriggered());
        // 第 3 个相邻时段合格，链重置；随后超标只计第 1 次。
        hour(10, "9");
        HourlyReadingResult afterReset = hour(11, "11");
        assertEquals(1, afterReset.chainIndex());
        assertFalse(afterReset.restrictionTriggered());
        assertEquals(0, count("restriction_order", ""));
    }

    @Test
    void duplicateHourlyReadingIsDeduplicated() {
        hour(8, "11");
        HourlyReadingResult duplicate = hour(8, "11");
        assertTrue(duplicate.deduplicated());
        assertEquals(1, count("monitor_reading", " AND period_type='HOUR'"));
    }

    @Test
    void exactlyTwoHourGapIsNotMarkedButLongerGapIsSupervised() {
        LocalDateTime from = LocalDateTime.of(2026, 9, 26, 8, 0);
        GapResult exactlyTwoHours = service.checkGap(new GapCheckRequest(CODE, from, from.plusHours(2)));
        assertEquals(120, exactlyTwoHours.gapMinutes());
        assertFalse(exactlyTwoHours.marked(), "恰 2 小时缺失不标记");

        GapResult longer = service.checkGap(new GapCheckRequest(CODE, from, from.plusHours(2).plusMinutes(1)));
        assertEquals(121, longer.gapMinutes());
        assertTrue(longer.marked(), "超过 2 小时缺失应督办");
        assertNotNull(longer.supervisedAt());
        assertEquals(1, count("data_gap", ""));
    }

    @Test
    void confirmBeforeDeadlineFreezesTotalQuantityAtomically() {
        hour(8, "11");
        hour(9, "12");
        HourlyReadingResult third = hour(10, "13");
        // 距下达 1 小时 59 分：尚在 2 小时确认期内。
        clock.setFixed(CemsTestSupport.T0.plusHours(1).plusMinutes(59));
        Receipt receipt = service.confirmOrder(new ConfirmOrderRequest("c-1", third.orderNo()));
        assertEquals("CONFIRM_RESTRICTION", receipt.action());

        OrderView order = service.getOrder(third.orderNo());
        assertEquals("ACTIVE", order.status());
        assertEquals(0, new BigDecimal("36").compareTo(order.frozenTotalQty()),
                "限排生效同事务冻结基数，应为截至生效时小时均值累计 36");
        // 同键同参重放返回首次结果，不重复生效。
        Receipt replay = service.confirmOrder(new ConfirmOrderRequest("c-1", third.orderNo()));
        assertEquals(receipt.action(), replay.action());
        assertEquals(1, count("restriction_order", ""));
    }

    @Test
    void orderNotConfirmedForExactlyTwoHoursIsEscalatedAndCutsProduction() {
        hour(8, "11");
        hour(9, "12");
        HourlyReadingResult third = hour(10, "13");

        // 恰满 2 小时当刻：确认应被拒绝，巡检升级。
        clock.setFixed(CemsTestSupport.T0.plusHours(2));
        assertThrows(ConflictException.class,
                () -> service.confirmOrder(new ConfirmOrderRequest("c-late", third.orderNo())));

        List<OrderView> escalated = service.escalateSweep(CODE);
        assertEquals(1, escalated.size());
        OrderView order = service.getOrder(third.orderNo());
        assertEquals("ESCALATED", order.status());
        assertNotNull(order.escalatedAt());
        assertNotNull(order.frozenTotalQty(), "升级同事务必须冻结基数，不得升级而基数未定");
        assertEquals(0, new BigDecimal("36").compareTo(order.frozenTotalQty()));
        Long cuts = jdbc.queryForObject("SELECT COUNT(*) FROM production_cut WHERE order_no = ?",
                Long.class, third.orderNo());
        assertEquals(1L, cuts, "升级必须同事务联动限产记录");
    }

    @Test
    void escalationSweepBeforeDeadlineDoesNothing() {
        hour(8, "11");
        hour(9, "12");
        hour(10, "13");
        clock.setFixed(CemsTestSupport.T0.plusHours(1).plusMinutes(59));
        List<OrderView> escalated = service.escalateSweep(CODE);
        assertTrue(escalated.isEmpty(), "未满 2 小时不得升级");
    }

    @Test
    void passingRetestReleasesRestrictionAndClosesOrderInOneTransaction() {
        hour(8, "11");
        hour(9, "12");
        HourlyReadingResult third = hour(10, "13");
        Receipt confirmed = service.confirmOrder(new ConfirmOrderRequest("c-2", third.orderNo()));
        assertNotNull(confirmed);

        clock.setFixed(CemsTestSupport.T0.plusHours(3));
        ReleaseResult released = service.release(new ReleaseRestrictionRequest(
                "rel-1", third.orderNo(), LocalDateTime.of(2026, 9, 26, 14, 0), new BigDecimal("9.5")));
        assertEquals("CLOSED", released.status());

        OrderView order = service.getOrder(third.orderNo());
        assertEquals("CLOSED", order.status());
        assertEquals(0, new BigDecimal("9.5").compareTo(order.retestValue()));
        assertEquals("MONITORING", service.getOutlet(CODE).status());
        assertEquals(0, count("exceedance_mark", " AND status='ACTIVE' AND mark_type='HOURLY_AVG'"));
        assertNull(service.getOutlet(CODE).archivedAt());
    }

    @Test
    void failingRetestRejectsReleaseWithoutChangingState() {
        hour(8, "11");
        hour(9, "12");
        HourlyReadingResult third = hour(10, "13");
        service.confirmOrder(new ConfirmOrderRequest("c-3", third.orderNo()));

        clock.setFixed(CemsTestSupport.T0.plusHours(3));
        ConflictException ex = assertThrows(ConflictException.class, () -> service.release(
                new ReleaseRestrictionRequest("rel-bad", third.orderNo(),
                        LocalDateTime.of(2026, 9, 26, 14, 0), new BigDecimal("10.01"))));
        assertTrue(ex.getMessage().contains("复测不合格"));
        // 事务回滚：限排令仍执行中、复测读数不得写入。
        assertEquals("ACTIVE", service.getOrder(third.orderNo()).status());
        assertEquals(0, count("monitor_reading", " AND period_type='HOUR' AND period_start = ?",
                java.sql.Timestamp.valueOf(LocalDateTime.of(2026, 9, 26, 14, 0))));
    }

    @Test
    void sameRequestReplaysFirstResultAndDifferentParamsConflictWithoutStateChange() {
        DailyEvalResult first = service.evaluateDaily(
                new DailyEvalRequest("idem-1", CODE, DAY, new BigDecimal("60")));
        assertTrue(first.exceeded());
        DailyEvalResult replay = service.evaluateDaily(
                new DailyEvalRequest("idem-1", CODE, DAY, new BigDecimal("60")));
        assertEquals(first.markId(), replay.markId());
        assertEquals(1, count("exceedance_mark", " AND mark_type='DAILY_AVG'"));
        assertEquals(1, count("monitor_reading", " AND period_type='DAY'"));

        assertThrows(ConflictException.class, () -> service.evaluateDaily(
                new DailyEvalRequest("idem-1", CODE, DAY, new BigDecimal("40"))));
        // 异参 409：原读数 60 与超标标记均不被改变。
        Map<String, Object> reading = jdbc.queryForMap(
                "SELECT avg_value FROM monitor_reading WHERE outlet_code = ? AND period_type='DAY'", CODE);
        assertEquals(0, new BigDecimal("60").compareTo((java.math.BigDecimal) reading.get("avg_value")));
        assertEquals(1, count("exceedance_mark", " AND mark_type='DAILY_AVG'"));
    }

    @Test
    void archiveIsTerminalIdempotentAndLateReadingsOnlyRecordEvents() {
        // 排口不存在：404，且不产生排口状态变化。
        assertThrows(NotFoundException.class, () -> service.archive(
                new ArchiveOutletRequest("arch-missing", "NOT-EXIST")));

        ArchiveResult archived = service.archive(new ArchiveOutletRequest("arch-1", CODE));
        assertTrue(archived.archived());
        OutletView view = service.getOutlet(CODE);
        assertEquals("ARCHIVED", view.status());

        // 同键重复归档：回放首次结果（幂等成功），归档时刻一致，不新增 ARCHIVE 事件。
        ArchiveResult replay = service.archive(new ArchiveOutletRequest("arch-1", CODE));
        assertEquals(archived.archivedAt(), replay.archivedAt());
        assertEquals(1, count("outlet_event", " AND event_type='ARCHIVE'"));

        // 异键再次归档：排口已是终态，返回 archived=false 且仍不新增事件、不改变归档时刻。
        ArchiveResult again = service.archive(new ArchiveOutletRequest("arch-2", CODE));
        assertFalse(again.archived());
        assertEquals(archived.archivedAt(), again.archivedAt());
        assertEquals(1, count("outlet_event", " AND event_type='ARCHIVE'"));

        // 归档后迟到监测数据只记迟到事件，不写读数、不评价。
        HourlyReadingResult lateHour = hour(20, "99");
        assertTrue(lateHour.late());
        DailyEvalResult lateDay = service.evaluateDaily(
                new DailyEvalRequest("late-d", CODE, DAY.plusDays(2), new BigDecimal("99")));
        assertTrue(lateDay.late());
        assertFalse(lateDay.exceeded());
        assertEquals(0, count("monitor_reading", ""));
        assertEquals(2, count("outlet_event", " AND event_type='LATE_READING' AND late_flag=TRUE"));
    }

    @Test
    void archiveBlockedWhileOrderActive() {
        hour(8, "11");
        hour(9, "12");
        hour(10, "13");
        assertThrows(ConflictException.class,
                () -> service.archive(new ArchiveOutletRequest("arch-2", CODE)));
    }

    @Test
    void recomputeRebuildsMarksBackfillsOrderAndSupervisesGapsAndIsStable() {
        LocalDateTime now = clock.now();
        // 直接构造读数：08/09/10 连续超标（10 点为链第 3 次），14 点超标（中间缺 11/12/13 三小时）。
        jdbc.update("INSERT INTO monitor_reading(outlet_code, period_type, period_start, avg_value, sample_count, created_at) "
                + "VALUES (?, 'HOUR', ?, 11, 1, ?)", CODE, java.sql.Timestamp.valueOf(LocalDateTime.of(2026, 9, 26, 8, 0)),
                java.sql.Timestamp.valueOf(now));
        jdbc.update("INSERT INTO monitor_reading(outlet_code, period_type, period_start, avg_value, sample_count, created_at) "
                + "VALUES (?, 'HOUR', ?, 12, 1, ?)", CODE, java.sql.Timestamp.valueOf(LocalDateTime.of(2026, 9, 26, 9, 0)),
                java.sql.Timestamp.valueOf(now));
        jdbc.update("INSERT INTO monitor_reading(outlet_code, period_type, period_start, avg_value, sample_count, created_at) "
                + "VALUES (?, 'HOUR', ?, 13, 1, ?)", CODE, java.sql.Timestamp.valueOf(LocalDateTime.of(2026, 9, 26, 10, 0)),
                java.sql.Timestamp.valueOf(now));
        jdbc.update("INSERT INTO monitor_reading(outlet_code, period_type, period_start, avg_value, sample_count, created_at) "
                + "VALUES (?, 'HOUR', ?, 20, 1, ?)", CODE, java.sql.Timestamp.valueOf(LocalDateTime.of(2026, 9, 26, 14, 0)),
                java.sql.Timestamp.valueOf(now));
        jdbc.update("INSERT INTO monitor_reading(outlet_code, period_type, period_start, avg_value, sample_count, created_at) "
                + "VALUES (?, 'DAY', ?, 60, 1, ?)", CODE, java.sql.Timestamp.valueOf(DAY.atStartOfDay()),
                java.sql.Timestamp.valueOf(now));

        RecomputeResult result = service.recompute(new RecomputeRequest(CODE));
        assertEquals(4, result.hourlyMarkCount());
        assertEquals(1, result.dailyMarkCount());
        assertEquals(1, result.activeOrderCount(), "链第 3 次应补建限排令");
        assertEquals(1, result.gapCount(), "缺 11/12/13 共 180 分钟应督办");

        // 再次复算结果稳定：限排记录与缺失督办不重复。
        RecomputeResult again = service.recompute(new RecomputeRequest(CODE));
        assertEquals(1, again.activeOrderCount());
        assertEquals(1, again.gapCount());
        assertEquals(4, again.hourlyMarkCount());

        // 复测解除后复算：历史超标标记保持 RESOLVED，关闭的令不被补建为新令。
        Map<String, Object> order = jdbc.queryForMap(
                "SELECT * FROM restriction_order WHERE outlet_code = ?", CODE);
        String orderNo = (String) order.get("order_no");
        clock.setFixed(now.plusMinutes(30));
        service.confirmOrder(new ConfirmOrderRequest("rc-1", orderNo));
        clock.setFixed(now.plusHours(4));
        service.release(new ReleaseRestrictionRequest("rrel-1", orderNo,
                LocalDateTime.of(2026, 9, 26, 16, 0), new BigDecimal("8")));
        RecomputeResult afterRelease = service.recompute(new RecomputeRequest(CODE));
        assertEquals(0, afterRelease.activeOrderCount());
        // 08/09/10/14 四个超标点均已随限排解除关闭，复算后保持 RESOLVED。
        assertEquals(4, jdbc.queryForObject(
                "SELECT COUNT(*) FROM exceedance_mark WHERE outlet_code = ? AND mark_type='HOURLY_AVG' AND status='RESOLVED'",
                Long.class, CODE));
    }
}
