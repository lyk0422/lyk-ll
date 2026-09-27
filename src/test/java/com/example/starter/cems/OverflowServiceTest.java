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

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import com.example.starter.cems.CemsTypes.ConfirmOrderRequest;
import com.example.starter.cems.CemsTypes.HourlyReadingRequest;
import com.example.starter.cems.CemsTypes.HourlyReadingResult;
import com.example.starter.cems.CemsTypes.OverflowAdjustmentRequest;
import com.example.starter.cems.CemsTypes.OverflowAdjustmentView;
import com.example.starter.cems.CemsTypes.OverflowHourEntry;
import com.example.starter.cems.CemsTypes.OverflowHourView;
import com.example.starter.cems.CemsTypes.OverflowHoursRequest;
import com.example.starter.cems.CemsTypes.OverflowHoursResult;
import com.example.starter.cems.CemsTypes.OverflowPermitRequest;
import com.example.starter.cems.CemsTypes.OverflowPermitView;
import com.example.starter.cems.CemsTypes.OverflowReviewRequest;
import com.example.starter.cems.CemsTypes.OverflowReviewResult;
import com.example.starter.cems.CemsTypes.OverflowSettlementRequest;
import com.example.starter.cems.CemsTypes.OverflowSnapshotView;
import com.example.starter.cems.CemsTypes.DischargeStatsView;
import com.example.starter.cems.CemsTypes.StatsCaliber;

/**
 * 雨季溢流证据标记与双口径统计业务测试：许可版本、合规溢流边界（恰等雨量、完整落窗、缺雨量待核）、
 * 复核冻结与独立调差、双口径统计（分母/缺点/空集合）、连续段断段与专项异常、旧证据保留。
 * 数据落在真实 H2（MODE=MySQL）；业务时刻均为 UTC。
 */
@SpringBootTest
class OverflowServiceTest {

    private static final String CODE = "OV-01";
    private static final LocalDate DAY = LocalDate.of(2026, 9, 26);

    @Autowired
    private CemsService cemsService;
    @Autowired
    private OverflowService service;
    @Autowired
    private CemsClock clock;
    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        CemsTestSupport.clean(jdbc);
        clock.reset();
        clock.setFixed(CemsTestSupport.T0);
        CemsTestSupport.register(cemsService, CODE, "50", "10");
    }

    private static LocalDateTime utc(int hour) {
        return LocalDateTime.of(2026, 9, 26, hour, 0);
    }

    private static LocalDateTime utc(int hour, int minute) {
        return LocalDateTime.of(2026, 9, 26, hour, minute);
    }

    private OverflowPermitView permit(String reqId, int expectedVersion, LocalDateTime from, LocalDateTime to,
                                      String minRainfall, int maxHours) {
        return service.upsertPermit(new OverflowPermitRequest(reqId, CODE, expectedVersion, from, to,
                new BigDecimal(minRainfall), maxHours));
    }

    private OverflowHoursResult report(String reqId, OverflowHourEntry... entries) {
        return service.reportHours(new OverflowHoursRequest(reqId, CODE, List.of(entries)));
    }

    private static OverflowHourEntry entry(int hour, String rainfall, boolean flag) {
        return new OverflowHourEntry(utc(hour), rainfall == null ? null : new BigDecimal(rainfall), flag);
    }

    private long count(String table, String where, Object... args) {
        Long c = jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE outlet_code = ?" + where,
                Long.class, mergeArgs(args));
        return c == null ? 0 : c;
    }

    private Object[] mergeArgs(Object[] args) {
        Object[] all = new Object[args.length + 1];
        all[0] = CODE;
        System.arraycopy(args, 0, all, 1, args.length);
        return all;
    }

    // ---------------- 许可版本 ----------------

    @Test
    void permitCreateModifyReplayAndVersionConflict() {
        OverflowPermitView v1 = permit("p-1", 0, utc(6), utc(12), "5", 3);
        assertEquals(1, v1.version(), "新增从版本 1 开始");

        // 同键同参重放：返回首次冻结结果，版本不再增加。
        OverflowPermitView replay = permit("p-1", 0, utc(6), utc(12), "5", 3);
        assertEquals(1, replay.version());
        assertEquals(1, count("overflow_permit", ""));

        // 同键异参：409 幂等冲突，且不产生新版本。
        assertThrows(ConflictException.class, () -> permit("p-1", 0, utc(6), utc(13), "5", 3));
        assertEquals(1, count("overflow_permit", ""));

        // 成功变更加 1。
        OverflowPermitView v2 = permit("p-2", 1, utc(6), utc(18), "6", 2);
        assertEquals(2, v2.version());
        assertEquals(0, new BigDecimal("6").compareTo(v2.minRainfall()));

        // expectedVersion 与当前版本不一致：409 版本冲突。
        ConflictException stale = assertThrows(ConflictException.class,
                () -> permit("p-3", 1, utc(6), utc(20), "6", 2));
        assertTrue(stale.getMessage().contains("版本冲突"));
        // 已存在许可时再按新增（expectedVersion=0）提交：同样版本冲突。
        assertThrows(ConflictException.class, () -> permit("p-4", 0, utc(6), utc(20), "6", 2));
        assertEquals(2, count("overflow_permit", ""));
    }

    @Test
    void permitValidationAndNotFound() {
        // 排口不存在：404。
        assertThrows(NotFoundException.class, () -> service.upsertPermit(new OverflowPermitRequest(
                "p-x", "NO-SUCH", 0, utc(6), utc(12), new BigDecimal("5"), 3)));
        // 窗口非法：validFrom 不早于 validTo。
        assertThrows(IllegalArgumentException.class, () -> permit("p-b1", 0, utc(12), utc(12), "5", 3));
        // 负雨量门槛。
        assertThrows(IllegalArgumentException.class,
                () -> permit("p-b2", 0, utc(6), utc(12), "-0.1", 3));
        // 最大连续小时数至少 1。
        assertThrows(IllegalArgumentException.class, () -> permit("p-b3", 0, utc(6), utc(12), "5", 0));
        // expectedVersion 缺失。
        assertThrows(IllegalArgumentException.class, () -> service.upsertPermit(new OverflowPermitRequest(
                "p-b4", CODE, null, utc(6), utc(12), new BigDecimal("5"), 3)));
        // 失败不占键：修正参数后同一 requestId 可成功。
        OverflowPermitView ok = permit("p-b3", 0, utc(6), utc(12), "5", 3);
        assertEquals(1, ok.version());
        // 无许可查询：404。
        CemsTestSupport.register(cemsService, "OV-NONE", "50", "10");
        assertThrows(NotFoundException.class, () -> service.getPermit("OV-NONE"));
    }

    // ---------------- 小时评估边界 ----------------

    @Test
    void compliantOverflowBoundaries() {
        permit("p-1", 0, utc(6), utc(12), "5", 3);
        OverflowHoursResult result = report("h-1",
                entry(7, "5.00", true),   // 恰等雨量合法
                entry(8, "4.99", true),   // 低于门槛
                entry(9, null, true),     // 缺雨量只能待核
                entry(11, "10", true),    // [11,12) 完整落窗
                entry(12, "10", true),    // [12,13) 超出窗口
                entry(10, "10", false));  // 非溢流
        List<OverflowHourView> hours = result.hours();
        assertEquals("COMPLIANT", hours.get(0).evalStatus(), "恰等雨量应判合规溢流");
        assertEquals("NON_COMPLIANT", hours.get(1).evalStatus());
        assertEquals("PENDING_REVIEW", hours.get(2).evalStatus(), "缺雨量只能待核，不当零也不豁免");
        assertEquals("COMPLIANT", hours.get(3).evalStatus());
        assertEquals("NON_COMPLIANT", hours.get(4).evalStatus(), "小时未完整落窗不合规");
        assertEquals("NORMAL", hours.get(5).evalStatus());
        assertEquals(1, hours.get(0).permitVersion(), "评估须记录所用许可版本");
    }

    @Test
    void hourMustBeFullyInsideHalfOpenWindow() {
        // 非整点窗口：只有 [07,08) 完整落窗。
        permit("p-1", 0, utc(6, 30), utc(8, 30), "5", 3);
        OverflowHoursResult result = report("h-1",
                entry(6, "10", true), entry(7, "10", true), entry(8, "10", true));
        assertEquals("NON_COMPLIANT", result.hours().get(0).evalStatus(), "[06,07) 未完整落窗");
        assertEquals("COMPLIANT", result.hours().get(1).evalStatus(), "[07,08) 完整落窗");
        assertEquals("NON_COMPLIANT", result.hours().get(2).evalStatus(), "[08,09) 未完整落窗");
    }

    @Test
    void missingPermitAndInvalidHour() {
        // 无许可：有雨量不合规，缺雨量待核。
        OverflowHoursResult result = report("h-1", entry(7, "10", true), entry(8, null, true));
        assertEquals("NON_COMPLIANT", result.hours().get(0).evalStatus());
        assertNull(result.hours().get(0).permitVersion());
        assertEquals("PENDING_REVIEW", result.hours().get(1).evalStatus());

        // 非 UTC 整点：400。
        assertThrows(IllegalArgumentException.class, () -> service.reportHours(new OverflowHoursRequest(
                "h-b1", CODE, List.of(new OverflowHourEntry(utc(7, 30), new BigDecimal("5"), true)))));
        // 负雨量：400。
        assertThrows(IllegalArgumentException.class, () -> service.reportHours(new OverflowHoursRequest(
                "h-b2", CODE, List.of(new OverflowHourEntry(utc(7), new BigDecimal("-1"), true)))));
        // 同一请求内整点重复：400。
        assertThrows(IllegalArgumentException.class, () -> service.reportHours(new OverflowHoursRequest(
                "h-b3", CODE, List.of(entry(7, "5", true), entry(7, "6", true)))));
        // 空集合：400。
        assertThrows(IllegalArgumentException.class,
                () -> service.reportHours(new OverflowHoursRequest("h-b4", CODE, List.of())));
        // 排口不存在：404。
        assertThrows(NotFoundException.class, () -> service.reportHours(new OverflowHoursRequest(
                "h-b5", "NO-SUCH", List.of(entry(7, "5", true)))));
    }

    // ---------------- 雨量更正与复核冻结 ----------------

    @Test
    void rainfallCorrectionBeforeReviewRecomputesAndBumpsVersion() {
        permit("p-1", 0, utc(6), utc(12), "5", 3);
        OverflowHourView pending = report("h-1", entry(7, null, true)).hours().get(0);
        assertEquals("PENDING_REVIEW", pending.evalStatus());
        assertEquals(1, pending.rainfallVersion());

        // 复核前更正雨量：雨量版本加 1 并重算为合规。
        OverflowHourView corrected = report("h-2", entry(7, "6", true)).hours().get(0);
        assertEquals("COMPLIANT", corrected.evalStatus());
        assertEquals(2, corrected.rainfallVersion());

        // 同键重放不重复加版本；异参 409。
        assertEquals(2, report("h-2", entry(7, "6", true)).hours().get(0).rainfallVersion());
        assertThrows(ConflictException.class, () -> report("h-2", entry(7, "7", true)));

        // 新键同参：雨量未变化，版本不再增加。
        assertEquals(2, report("h-3", entry(7, "6", true)).hours().get(0).rainfallVersion());
    }

    @Test
    void reviewFreezesSnapshotThenOnlyIndependentAdjustment() {
        permit("p-1", 0, utc(6), utc(12), "5", 3);
        report("h-1", entry(7, "6", true), entry(8, null, true));
        cemsService.reportHourly(new HourlyReadingRequest(CODE, utc(7), new BigDecimal("20")));
        cemsService.reportHourly(new HourlyReadingRequest(CODE, utc(8), new BigDecimal("30")));

        OverflowReviewResult review = service.review(new OverflowReviewRequest("r-1", CODE, DAY));
        assertEquals(2, review.reviewedHours());
        assertEquals("REVIEW", review.snapshot().frozenBy());
        assertEquals(1, review.snapshot().permitVersion());
        assertTrue(review.snapshot().rainfallVersions().contains("07:00"));
        // 双口径：全口径 (20+30)/2=25；正常口径排除合规溢流 07 点 → 30。
        assertEquals(0, new BigDecimal("25").compareTo(review.snapshot().allAvg()));
        assertEquals(0, new BigDecimal("30").compareTo(review.snapshot().normalAvg()));

        // 冻结后更正雨量：422 业务拒绝。
        assertThrows(UnprocessableException.class, () -> report("h-2", entry(7, "9", true)));
        // 冻结后只能独立调差，且不改日结快照。
        OverflowAdjustmentView adj = service.adjust(new OverflowAdjustmentRequest(
                "a-1", CODE, utc(7), new BigDecimal("-1.5"), "人工核减"));
        assertTrue(adj.id() > 0);
        OverflowSnapshotView after = service.getSnapshot(CODE, DAY);
        assertEquals(0, new BigDecimal("25").compareTo(after.allAvg()), "调差不改日结快照");
        assertEquals(0, new BigDecimal("30").compareTo(after.normalAvg()));
        // 调差幂等：同键同参重放同一结果，异参 409。
        assertEquals(adj.id(), service.adjust(new OverflowAdjustmentRequest(
                "a-1", CODE, utc(7), new BigDecimal("-1.5"), "人工核减")).id());
        assertThrows(ConflictException.class, () -> service.adjust(new OverflowAdjustmentRequest(
                "a-1", CODE, utc(7), new BigDecimal("-2"), "人工核减")));
    }

    @Test
    void adjustmentRejectedBeforeFreeze() {
        permit("p-1", 0, utc(6), utc(12), "5", 3);
        report("h-1", entry(7, "6", true));
        assertThrows(UnprocessableException.class, () -> service.adjust(new OverflowAdjustmentRequest(
                "a-1", CODE, utc(7), new BigDecimal("1"), "冻结前调差")));
    }

    @Test
    void reviewAndSettlementCompeteAndFreezeConsistentDualCaliber() {
        permit("p-1", 0, utc(0), utc(0).plusHours(24), "5", 3);
        report("h-1", entry(7, "6", true));
        cemsService.reportHourly(new HourlyReadingRequest(CODE, utc(7), new BigDecimal("20")));
        cemsService.reportHourly(new HourlyReadingRequest(CODE, utc(8), new BigDecimal("40")));

        // 日结先冻：复核返回同一冻结快照（frozenBy 保持 SETTLEMENT，双口径一致）。
        OverflowSnapshotView settled = service.settle(new OverflowSettlementRequest("s-1", CODE, DAY));
        assertEquals("SETTLEMENT", settled.frozenBy());
        OverflowReviewResult review = service.review(new OverflowReviewRequest("r-1", CODE, DAY));
        assertEquals("SETTLEMENT", review.snapshot().frozenBy());
        assertEquals(0, settled.allAvg().compareTo(review.snapshot().allAvg()));
        assertEquals(0, settled.normalAvg().compareTo(review.snapshot().normalAvg()));
        assertEquals(settled.normalCount(), review.snapshot().normalCount());
        assertEquals(1, count("overflow_daily_snapshot", ""));

        // 反向：另一排口复核先冻，日结返回同一快照。
        CemsTestSupport.register(cemsService, "OV-02", "50", "10");
        service.upsertPermit(new OverflowPermitRequest("p2-1", "OV-02", 0, utc(0), utc(0).plusHours(24),
                new BigDecimal("5"), 3));
        service.reportHours(new OverflowHoursRequest("h2-1", "OV-02",
                List.of(new OverflowHourEntry(utc(7), new BigDecimal("6"), true))));
        cemsService.reportHourly(new HourlyReadingRequest("OV-02", utc(7), new BigDecimal("20")));
        OverflowReviewResult review2 = service.review(new OverflowReviewRequest("r2-1", "OV-02", DAY));
        OverflowSnapshotView settle2 = service.settle(new OverflowSettlementRequest("s2-1", "OV-02", DAY));
        assertEquals("REVIEW", settle2.frozenBy());
        assertEquals(0, review2.snapshot().allAvg().compareTo(settle2.allAvg()));
        // OV-02 仅 07 点读数且为合规溢流：正常口径为空集合，两端均无结果（null）而非零。
        assertNull(review2.snapshot().normalAvg());
        assertNull(settle2.normalAvg());
        assertEquals(0, review2.snapshot().normalCount());
        assertEquals(0, settle2.normalCount());

        // 冻结后更正雨量：422。
        assertThrows(UnprocessableException.class, () -> service.reportHours(new OverflowHoursRequest(
                "h2-2", "OV-02", List.of(new OverflowHourEntry(utc(7), new BigDecimal("9"), true)))));
    }

    // ---------------- 双口径统计 ----------------

    @Test
    void dualCaliberStatsExcludeOnlyFullyEvidencedCompliantOverflow() {
        permit("p-1", 0, utc(6), utc(12), "5", 3);
        // 浓度读数 06..11 共 6 小时：10/20/30/40/50/60。
        int[] values = {10, 20, 30, 40, 50, 60};
        for (int i = 0; i < values.length; i++) {
            cemsService.reportHourly(new HourlyReadingRequest(
                    CODE, utc(6 + i), new BigDecimal(values[i])));
        }
        // 07 合规溢流（证据齐全）；08 缺雨量待核（证据不齐全，不排除）。
        report("h-1", entry(7, "5", true), entry(8, null, true));

        DischargeStatsView all = service.stats(CODE, utc(6), utc(12), StatsCaliber.ALL);
        assertEquals(6, all.denominator(), "全口径分母为全部读数小时");
        assertEquals(0, new BigDecimal("35").compareTo(all.average()));
        assertTrue(all.missingHours().isEmpty());
        assertTrue(all.excludedCompliantOverflowHours().isEmpty(), "全口径不排除任何小时");

        DischargeStatsView normal = service.stats(CODE, utc(6), utc(12), StatsCaliber.NORMAL);
        assertEquals(5, normal.denominator(), "正常口径仅排除证据齐全的合规溢流小时");
        assertEquals(0, new BigDecimal("38").compareTo(normal.average()));
        assertEquals(List.of(utc(7)), normal.excludedCompliantOverflowHours());
        assertFalse(normal.excludedCompliantOverflowHours().contains(utc(8)), "待核小时证据不齐全，不排除");

        // 缺点明示：区间扩到 14 点，12/13 点无读数。
        DischargeStatsView withGaps = service.stats(CODE, utc(6), utc(14), StatsCaliber.ALL);
        assertEquals(List.of(utc(12), utc(13)), withGaps.missingHours());
        assertEquals(6, withGaps.denominator());

        // 空集合：无结果而非零。
        DischargeStatsView empty = service.stats(CODE,
                LocalDateTime.of(2026, 9, 27, 6, 0), LocalDateTime.of(2026, 9, 27, 12, 0),
                StatsCaliber.NORMAL);
        assertNull(empty.average(), "空集合返回无结果而非零");
        assertEquals(0, empty.denominator());
        assertEquals(6, empty.missingHours().size());
    }

    @Test
    void oldOutletWithoutOverflowDataBehavesUnchanged() {
        // 旧排口无溢流数据：两种口径一致，均包含全部读数。
        for (int h = 6; h <= 8; h++) {
            cemsService.reportHourly(new HourlyReadingRequest(CODE, utc(h), new BigDecimal("9")));
        }
        DischargeStatsView all = service.stats(CODE, utc(6), utc(9), StatsCaliber.ALL);
        DischargeStatsView normal = service.stats(CODE, utc(6), utc(9), StatsCaliber.NORMAL);
        assertEquals(0, new BigDecimal("9").compareTo(all.average()));
        assertEquals(0, new BigDecimal("9").compareTo(normal.average()));
        assertEquals(3, normal.denominator());
    }

    // ---------------- 连续段断段与专项异常 ----------------

    @Test
    void segmentExceedingMaxConsecutiveHoursRaisesAnomaly() {
        permit("p-1", 0, utc(0), utc(0).plusDays(1), "5", 3);
        report("h-1", entry(6, "6", true), entry(7, "6", true), entry(8, "6", true), entry(9, "6", true));
        OverflowReviewResult review = service.review(new OverflowReviewRequest("r-1", CODE, DAY));
        assertEquals(1, review.anomalies().size(), "连续 4 小时超过最长 3 小时应产生专项异常");
        assertEquals(utc(6), review.anomalies().get(0).segmentStart());
        assertEquals(utc(10), review.anomalies().get(0).segmentEnd());
        assertEquals(4, review.anomalies().get(0).segmentHours());
        assertEquals(3, review.anomalies().get(0).maxAllowed());
        assertEquals(1, count("overflow_anomaly", ""));
    }

    @Test
    void gapBreaksSegmentSoNoAnomaly() {
        permit("p-1", 0, utc(0), utc(0).plusDays(1), "5", 3);
        // 06/07 合规，08 缺点（无数据），09/10 合规：两段各 2 小时，均不超最长 3。
        report("h-1", entry(6, "6", true), entry(7, "6", true), entry(9, "6", true), entry(10, "6", true));
        OverflowReviewResult review = service.review(new OverflowReviewRequest("r-1", CODE, DAY));
        assertTrue(review.anomalies().isEmpty(), "出现缺点必须断段，两段均不超最长段");
        assertEquals(0, count("overflow_anomaly", ""));
    }

    @Test
    void permitChangeBreaksSegmentAndNewPermitMaxApplies() {
        permit("p-1", 0, utc(0), utc(0).plusDays(1), "5", 3);
        report("h-1", entry(6, "6", true), entry(7, "6", true), entry(8, "6", true));
        // 许可变更（版本 2，最长连续 2 小时）：后续小时按新版本评估。
        permit("p-2", 1, utc(0), utc(0).plusDays(1), "5", 2);
        report("h-2", entry(9, "6", true), entry(10, "6", true), entry(11, "6", true));

        OverflowReviewResult review = service.review(new OverflowReviewRequest("r-1", CODE, DAY));
        // 跨许可断段：[06,09) 版本 1 共 3 小时不超 3；[09,12) 版本 2 共 3 小时超 2 → 一个专项异常。
        assertEquals(1, review.anomalies().size());
        assertEquals(utc(9), review.anomalies().get(0).segmentStart());
        assertEquals(3, review.anomalies().get(0).segmentHours());
        assertEquals(2, review.anomalies().get(0).maxAllowed());
    }

    // ---------------- 旧证据保留 ----------------

    @Test
    void overflowNeverCancelsRestrictionOrderOrExceedanceMarks() {
        // 旧流程：恰连续 3 次小时超标自动下达限排令并确认执行。
        cemsService.reportHourly(new HourlyReadingRequest(CODE, utc(8), new BigDecimal("11")));
        cemsService.reportHourly(new HourlyReadingRequest(CODE, utc(9), new BigDecimal("12")));
        HourlyReadingResult third = cemsService.reportHourly(
                new HourlyReadingRequest(CODE, utc(10), new BigDecimal("13")));
        assertTrue(third.restrictionTriggered());
        cemsService.confirmOrder(new ConfirmOrderRequest("c-1", third.orderNo()));

        // 雨季溢流：相同时段标记合规溢流并复核冻结。
        permit("p-1", 0, utc(6), utc(12), "5", 3);
        report("h-1", entry(8, "6", true), entry(9, "6", true), entry(10, "6", true));
        service.review(new OverflowReviewRequest("r-1", CODE, DAY));

        // 原排放浓度、超标链与限排令永远保留：溢流不取消限排、不减轻超标判定。
        assertEquals("ACTIVE", cemsService.getOrder(third.orderNo()).status(), "溢流不取消已生效限排令");
        assertEquals("RESTRICTED", cemsService.getOutlet(CODE).status());
        assertEquals(3, count("exceedance_mark", " AND mark_type='HOURLY_AVG' AND status='ACTIVE'"));
        assertEquals(3, count("monitor_reading", " AND period_type='HOUR'"));
        // 双口径仍基于原浓度：全口径 12，正常口径排除 3 个合规溢流小时后为空集合（无结果而非零）。
        DischargeStatsView all = service.stats(CODE, utc(8), utc(11), StatsCaliber.ALL);
        assertEquals(0, new BigDecimal("12").compareTo(all.average()));
        DischargeStatsView normal = service.stats(CODE, utc(8), utc(11), StatsCaliber.NORMAL);
        assertNull(normal.average());
        assertEquals(0, normal.denominator());
    }

    @Test
    void archivedOutletRejectsOverflowBusiness() {
        cemsService.archive(new com.example.starter.cems.CemsTypes.ArchiveOutletRequest("arch-1", CODE));
        assertThrows(UnprocessableException.class, () -> permit("p-1", 0, utc(6), utc(12), "5", 3));
        assertThrows(UnprocessableException.class, () -> report("h-1", entry(7, "5", true)));
        assertThrows(UnprocessableException.class,
                () -> service.review(new OverflowReviewRequest("r-1", CODE, DAY)));
        assertThrows(UnprocessableException.class,
                () -> service.settle(new OverflowSettlementRequest("s-1", CODE, DAY)));
    }

    @Test
    void snapshotQueryAndMissingSnapshot() {
        permit("p-1", 0, utc(6), utc(12), "5", 3);
        assertThrows(NotFoundException.class, () -> service.getSnapshot(CODE, DAY));
        service.settle(new OverflowSettlementRequest("s-1", CODE, DAY));
        OverflowSnapshotView snapshot = service.getSnapshot(CODE, DAY);
        assertEquals("SETTLEMENT", snapshot.frozenBy());
        assertNull(snapshot.allAvg(), "无读数时空集合为无结果而非零");
        assertEquals(24, snapshot.missingHours().size(), "缺点明示：全日 24 个整点均无读数");
    }
}
