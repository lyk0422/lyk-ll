package com.example.starter.cems;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import com.example.starter.cems.CemsTypes.DailyEvalRequest;
import com.example.starter.cems.CemsTypes.HourlyReadingRequest;
import com.example.starter.cems.CemsTypes.HourlyReadingResult;
import com.example.starter.cems.OverflowTypes.AveragesView;
import com.example.starter.cems.OverflowTypes.HourReportRequest;
import com.example.starter.cems.OverflowTypes.HourReportResult;
import com.example.starter.cems.OverflowTypes.OverflowHourItem;
import com.example.starter.cems.OverflowTypes.PermitUpsertRequest;
import com.example.starter.cems.OverflowTypes.PermitView;
import com.example.starter.cems.OverflowTypes.RainfallCorrectionRequest;
import com.example.starter.cems.OverflowTypes.RainfallCorrectionResult;
import com.example.starter.cems.OverflowTypes.ReviewFreezeRequest;
import com.example.starter.cems.OverflowTypes.ReviewView;

/**
 * 雨季溢流主流程与失败分支测试：许可版本、合规溢流证据边界（恰等雨量合法、完整落窗、缺雨量待核）、
 * 双口径均值（分母与缺点明示、空集合无结果）、复核冻结与独立调差、连续分段与专项异常、
 * 幂等回放/异参 409/失败不占键，以及溢流不改变原超标链与限排令。数据落在真实 H2（MODE=MySQL）。
 */
@SpringBootTest
class OverflowServiceTest {

    private static final String CODE = "OUT-OF";
    private static final LocalDate DAY = LocalDate.of(2026, 9, 26);

    @Autowired
    private OverflowService service;
    @Autowired
    private CemsService cemsService;
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

    private static Instant utc(String text) {
        return Instant.parse(text);
    }

    private PermitView permit(int expectedVersion, String startUtc, String endUtc,
                              String minRainfall, int maxHours) {
        return service.upsertPermit(new PermitUpsertRequest(
                "permit-" + expectedVersion + "-" + startUtc + "-" + endUtc + "-" + minRainfall,
                CODE, expectedVersion,
                utc(startUtc), utc(endUtc), new BigDecimal(minRainfall), maxHours));
    }

    private HourReportResult report(String requestId, OverflowHourItem... items) {
        return service.reportHours(new HourReportRequest(requestId, CODE, List.of(items)));
    }

    private static OverflowHourItem item(String hourUtc, String rainfall, boolean overflow, String value) {
        return new OverflowHourItem(utc(hourUtc),
                rainfall == null ? null : new BigDecimal(rainfall), overflow, new BigDecimal(value));
    }

    private static void assertBd(String expected, BigDecimal actual) {
        assertNotNull(actual, "均值不应为空");
        assertEquals(0, new BigDecimal(expected).compareTo(actual),
                "期望值 " + expected + " 实际 " + actual);
    }

    private long count(String table) {
        Long c = jdbc.queryForObject(
                "SELECT COUNT(*) FROM " + table + " WHERE outlet_code = ?", Long.class, CODE);
        return c == null ? 0 : c;
    }

    // ---------------- 许可版本 ----------------

    @Test
    void permitVersionStartsAtOneAndExpectedVersionGuardsChanges() {
        PermitView v1 = permit(0, "2026-09-26T08:00:00Z", "2026-09-26T12:00:00Z", "5", 3);
        assertEquals(1, v1.version());
        assertEquals("ACTIVE", v1.status());

        PermitView v2 = permit(1, "2026-09-26T08:00:00Z", "2026-09-26T20:00:00Z", "6", 2);
        assertEquals(2, v2.version());
        assertEquals(2, service.getCurrentPermit(CODE).version());
        assertEquals("SUPERSEDED", jdbc.queryForObject(
                "SELECT status FROM overflow_permit WHERE outlet_code = ? AND version = 1",
                String.class, CODE));

        // 版本从 1 开始、成功变更加 1；expectedVersion 不匹配 → 409 冲突
        assertThrows(ConflictException.class, () -> permit(1, "2026-09-26T08:00:00Z",
                "2026-09-26T21:00:00Z", "6", 2));
        assertThrows(ConflictException.class, () -> permit(0, "2026-09-26T08:00:00Z",
                "2026-09-26T21:00:00Z", "6", 2));
        assertEquals(2, count("overflow_permit"), "版本冲突不得写入新版本");
    }

    @Test
    void permitValidationRejectsBadParamsAndUnknownOutlet() {
        assertThrows(IllegalArgumentException.class, () -> service.upsertPermit(
                new PermitUpsertRequest("p-bad-1", CODE, null,
                        utc("2026-09-26T08:00:00Z"), utc("2026-09-26T12:00:00Z"),
                        new BigDecimal("5"), 3)));
        assertThrows(IllegalArgumentException.class, () -> service.upsertPermit(
                new PermitUpsertRequest("p-bad-2", CODE, -1,
                        utc("2026-09-26T08:00:00Z"), utc("2026-09-26T12:00:00Z"),
                        new BigDecimal("5"), 3)));
        // 窗口终点必须晚于起点（半开窗口）
        assertThrows(IllegalArgumentException.class, () -> service.upsertPermit(
                new PermitUpsertRequest("p-bad-3", CODE, 0,
                        utc("2026-09-26T12:00:00Z"), utc("2026-09-26T12:00:00Z"),
                        new BigDecimal("5"), 3)));
        assertThrows(IllegalArgumentException.class, () -> service.upsertPermit(
                new PermitUpsertRequest("p-bad-4", CODE, 0,
                        utc("2026-09-26T08:00:00Z"), utc("2026-09-26T12:00:00Z"),
                        new BigDecimal("-1"), 3)));
        assertThrows(IllegalArgumentException.class, () -> service.upsertPermit(
                new PermitUpsertRequest("p-bad-5", CODE, 0,
                        utc("2026-09-26T08:00:00Z"), utc("2026-09-26T12:00:00Z"),
                        new BigDecimal("5"), 0)));
        assertThrows(NotFoundException.class, () -> service.upsertPermit(
                new PermitUpsertRequest("p-bad-6", "NO-SUCH", 0,
                        utc("2026-09-26T08:00:00Z"), utc("2026-09-26T12:00:00Z"),
                        new BigDecimal("5"), 3)));
        assertEquals(0, count("overflow_permit"), "参数非法不得写入许可");
    }

    // ---------------- 合规溢流证据边界 ----------------

    @Test
    void compliantOverflowRequiresPermitWindowAndRainfallEvidence() {
        permit(0, "2026-09-26T08:00:00Z", "2026-09-26T12:00:00Z", "5", 3);

        // 恰等雨量门槛合法
        HourReportResult ok = report("h-eq", item("2026-09-26T09:00:00Z", "5", true, "20"));
        assertEquals("COMPLIANT", ok.hours().get(0).markStatus());
        assertEquals(1, ok.hours().get(0).permitVersion());

        // 雨量低于门槛 → 422 业务拒绝
        assertThrows(BusinessException.class,
                () -> report("h-low", item("2026-09-26T10:00:00Z", "4.9", true, "20")));
        // 窗口之外 → 422
        assertThrows(BusinessException.class,
                () -> report("h-out", item("2026-09-26T13:00:00Z", "10", true, "20")));
        // 缺雨量只能待核，不当零也不豁免
        HourReportResult pending = report("h-pending", item("2026-09-26T11:00:00Z", null, true, "30"));
        assertEquals("PENDING", pending.hours().get(0).markStatus());
        assertNull(pending.hours().get(0).permitVersion());
        // 非溢流小时 → NORMAL
        HourReportResult normal = report("h-normal", item("2026-09-26T08:00:00Z", "1", false, "10"));
        assertEquals("NORMAL", normal.hours().get(0).markStatus());
        // 失败请求不落小时数据
        assertEquals(3, count("overflow_hour"));
    }

    @Test
    void hourMustFullyFitInsideHalfOpenWindow() {
        permit(0, "2026-09-26T08:30:00Z", "2026-09-26T10:30:00Z", "5", 3);
        // [08:00,09:00) 未完整落窗（窗口 08:30 起）
        assertThrows(BusinessException.class,
                () -> report("pw-1", item("2026-09-26T08:00:00Z", "6", true, "20")));
        // [09:00,10:00) 完整落窗
        assertEquals("COMPLIANT", report("pw-2",
                item("2026-09-26T09:00:00Z", "6", true, "20")).hours().get(0).markStatus());
        // [10:00,11:00) 超出窗口终点 10:30
        assertThrows(BusinessException.class,
                () -> report("pw-3", item("2026-09-26T10:00:00Z", "6", true, "20")));
    }

    @Test
    void overflowMarkRequiresActivePermit() {
        assertThrows(BusinessException.class,
                () -> report("np-1", item("2026-09-26T09:00:00Z", "10", true, "20")));
        // 缺雨量无许可也可登记待核（证据待补，不豁免）
        assertEquals("PENDING", report("np-2",
                item("2026-09-26T09:00:00Z", null, true, "20")).hours().get(0).markStatus());
    }

    @Test
    void duplicateHourDeduplicatedAndDifferentDataConflicts() {
        permit(0, "2026-09-26T08:00:00Z", "2026-09-26T12:00:00Z", "5", 3);
        report("d-1", item("2026-09-26T09:00:00Z", "6", true, "20"));
        HourReportResult dup = report("d-2", item("2026-09-26T09:00:00Z", "6", true, "20"));
        assertEquals(0, dup.reported());
        assertEquals(1, dup.deduplicated());
        assertTrue(dup.hours().get(0).deduplicated());
        assertThrows(ConflictException.class,
                () -> report("d-3", item("2026-09-26T09:00:00Z", "6", true, "21")));
        assertThrows(ConflictException.class,
                () -> report("d-4", item("2026-09-26T09:00:00Z", "7", true, "20")));
        assertEquals(1, count("overflow_hour"));
    }

    @Test
    void hourValidationRejectsNonWholeHourAndBadValues() {
        permit(0, "2026-09-26T08:00:00Z", "2026-09-26T12:00:00Z", "5", 3);
        assertThrows(IllegalArgumentException.class,
                () -> report("v-1", item("2026-09-26T09:30:00Z", "6", true, "20")));
        assertThrows(IllegalArgumentException.class, () -> report("v-2",
                new OverflowHourItem(utc("2026-09-26T09:00:00Z"), new BigDecimal("6"), null,
                        new BigDecimal("20"))));
        assertThrows(IllegalArgumentException.class,
                () -> report("v-3", item("2026-09-26T09:00:00Z", "-1", true, "20")));
        assertThrows(IllegalArgumentException.class, () -> report("v-4",
                item("2026-09-26T09:00:00Z", "6", true, "20"),
                item("2026-09-26T09:00:00Z", "6", true, "20")));
        assertThrows(IllegalArgumentException.class,
                () -> service.reportHours(new HourReportRequest("v-5", CODE, List.of())));
        assertEquals(0, count("overflow_hour"));
    }

    // ---------------- 双口径均值查询 ----------------

    @Test
    void dualCaliberAveragesExcludeOnlyFullyEvidencedCompliantHours() {
        permit(0, "2026-09-26T00:00:00Z", "2026-09-27T00:00:00Z", "5", 10);
        report("av-1",
                item("2026-09-26T08:00:00Z", "1", false, "10"),
                item("2026-09-26T09:00:00Z", "6", true, "20"),
                item("2026-09-26T10:00:00Z", null, true, "30"),
                item("2026-09-26T11:00:00Z", "2", false, "40"));

        AveragesView view = service.averages(CODE,
                utc("2026-09-26T08:00:00Z"), utc("2026-09-26T13:00:00Z"));
        assertEquals(5, view.totalSlots());
        assertEquals(4, view.reportedCount());
        assertEquals(1, view.missingCount(), "缺点：12:00 无记录");
        assertEquals(List.of(utc("2026-09-26T12:00:00Z")), view.missingHours());
        assertEquals(1, view.pendingCount(), "待核小时明示");
        // 全部口径：不排除任何已上报小时
        assertEquals(4, view.allCount());
        assertBd("25", view.allAvg());
        // 正常口径：仅排除证据齐全的合规溢流（09 时），待核（10 时）不排除
        assertEquals(3, view.normalCount());
        assertBd("26.6667", view.normalAvg());
    }

    @Test
    void emptyRangeReturnsNoResultInsteadOfZero() {
        AveragesView view = service.averages(CODE,
                utc("2027-01-01T00:00:00Z"), utc("2027-01-01T03:00:00Z"));
        assertEquals(3, view.totalSlots());
        assertEquals(0, view.reportedCount());
        assertEquals(3, view.missingCount());
        assertEquals(0, view.normalCount());
        assertEquals(0, view.allCount());
        assertNull(view.normalAvg(), "空集合返回无结果而非零");
        assertNull(view.allAvg(), "空集合返回无结果而非零");
    }

    @Test
    void averagesValidationRejectsBadRange() {
        assertThrows(IllegalArgumentException.class, () -> service.averages(CODE,
                utc("2026-09-26T08:30:00Z"), utc("2026-09-26T13:00:00Z")));
        assertThrows(IllegalArgumentException.class, () -> service.averages(CODE,
                utc("2026-09-26T13:00:00Z"), utc("2026-09-26T08:00:00Z")));
        assertThrows(NotFoundException.class, () -> service.averages("NO-SUCH",
                utc("2026-09-26T08:00:00Z"), utc("2026-09-26T13:00:00Z")));
    }

    // ---------------- 雨量更正与复核冻结 ----------------

    @Test
    void rainfallCorrectionRecomputesMarksBeforeFreeze() {
        permit(0, "2026-09-26T08:00:00Z", "2026-09-26T12:00:00Z", "5", 3);
        report("rc-1", item("2026-09-26T09:00:00Z", null, true, "20"));

        RainfallCorrectionResult r1 = service.correctRainfall(new RainfallCorrectionRequest(
                "rc-2", CODE, utc("2026-09-26T09:00:00Z"), new BigDecimal("6"), 1));
        assertFalse(r1.frozen());
        assertEquals("COMPLIANT", r1.markStatus());
        assertEquals(2, r1.rainfallVersion(), "成功更正雨量版本加 1");
        assertFalse(r1.adjustmentRecorded());

        // 版本不匹配 → 409
        assertThrows(ConflictException.class, () -> service.correctRainfall(
                new RainfallCorrectionRequest("rc-3", CODE, utc("2026-09-26T09:00:00Z"),
                        new BigDecimal("7"), 1)));
        // 更正后雨量低于门槛 → 重算降级 NORMAL（更正是数据维护，不拒绝）
        RainfallCorrectionResult r2 = service.correctRainfall(new RainfallCorrectionRequest(
                "rc-4", CODE, utc("2026-09-26T09:00:00Z"), new BigDecimal("3"), 2));
        assertEquals("NORMAL", r2.markStatus());
        assertEquals(3, r2.rainfallVersion());
        // 小时不存在 → 404
        assertThrows(NotFoundException.class, () -> service.correctRainfall(
                new RainfallCorrectionRequest("rc-5", CODE, utc("2026-09-26T10:00:00Z"),
                        new BigDecimal("3"), 3)));
    }

    @Test
    void freezeSnapshotsDualCaliberAndRejectsPendingHours() {
        permit(0, "2026-09-26T00:00:00Z", "2026-09-27T00:00:00Z", "5", 10);
        report("fz-1",
                item("2026-09-26T08:00:00Z", "1", false, "10"),
                item("2026-09-26T09:00:00Z", "6", true, "20"),
                item("2026-09-26T10:00:00Z", null, true, "30"));

        // 存在待核小时（缺雨量）不能复核冻结 → 422
        assertThrows(BusinessException.class,
                () -> service.freezeReview(new ReviewFreezeRequest("fz-2", CODE, DAY)));
        assertEquals(0, count("overflow_review"));

        // 复核前更正雨量须重算
        service.correctRainfall(new RainfallCorrectionRequest(
                "fz-3", CODE, utc("2026-09-26T10:00:00Z"), new BigDecimal("6"), 1));

        ReviewView review = service.freezeReview(new ReviewFreezeRequest("fz-4", CODE, DAY));
        assertEquals("FROZEN", review.status());
        assertEquals(1, review.permitVersion(), "冻结所用许可版本");
        assertEquals(2, review.rainfallVersion(), "冻结所用雨量版本");
        assertEquals(3, review.allCount());
        assertBd("20", review.allAvg());
        assertEquals(1, review.normalCount(), "仅 08 时非合规溢流计入正常口径");
        assertBd("10", review.normalAvg());
        assertEquals(21, review.missingCount());

        // 重复冻结（不同 requestId）→ 409
        assertThrows(ConflictException.class,
                () -> service.freezeReview(new ReviewFreezeRequest("fz-5", CODE, DAY)));
    }

    @Test
    void correctionAfterFreezeOnlyRecordsIndependentAdjustment() {
        permit(0, "2026-09-26T00:00:00Z", "2026-09-27T00:00:00Z", "5", 10);
        report("af-1",
                item("2026-09-26T08:00:00Z", "1", false, "10"),
                item("2026-09-26T09:00:00Z", "6", true, "20"));
        ReviewView frozen = service.freezeReview(new ReviewFreezeRequest("af-2", CODE, DAY));
        assertEquals(1, frozen.rainfallVersion());

        // 冻结后更正雨量：只登记独立调差，不改日结快照与已冻结小时
        RainfallCorrectionResult r = service.correctRainfall(new RainfallCorrectionRequest(
                "af-3", CODE, utc("2026-09-26T08:00:00Z"), new BigDecimal("9"), 1));
        assertTrue(r.frozen());
        assertTrue(r.adjustmentRecorded());
        assertEquals(2, r.rainfallVersion());
        assertEquals("NORMAL", r.markStatus(), "已冻结小时标记保持不动");

        assertEquals(1, count("overflow_adjustment"));
        assertEquals(1, jdbc.queryForObject(
                "SELECT rainfall_mm FROM overflow_hour WHERE outlet_code = ? AND hour_utc = ?",
                BigDecimal.class, CODE, java.sql.Timestamp.valueOf("2026-09-26 08:00:00"))
                .intValue(), "冻结后小时雨量不被改写");
        ReviewView after = service.getReview(CODE, DAY);
        assertBd("15", after.allAvg());
        assertEquals(1, after.rainfallVersion(), "日结快照雨量版本保持冻结值");
        assertEquals(1, count("overflow_review"));
    }

    // ---------------- 连续分段与专项异常 ----------------

    @Test
    void segmentsBreakAcrossPermitsAndGapsAndOverrunRaisesAnomaly() {
        permit(0, "2026-09-26T00:00:00Z", "2026-09-27T00:00:00Z", "5", 2);
        // 连续 3 小时合规溢流，超过最长连续 2 小时 → 专项异常
        report("sg-1",
                item("2026-09-26T08:00:00Z", "6", true, "20"),
                item("2026-09-26T09:00:00Z", "6", true, "20"),
                item("2026-09-26T10:00:00Z", "6", true, "20"));
        var anomalies = service.listAnomalies(CODE);
        assertEquals(1, anomalies.size());
        assertEquals(3, anomalies.get(0).hourCount());
        assertEquals(2, anomalies.get(0).maxAllowed());
        assertEquals(1, anomalies.get(0).permitVersion());
        assertEquals(utc("2026-09-26T08:00:00Z"), anomalies.get(0).segmentStartUtc());
        assertEquals(utc("2026-09-26T11:00:00Z"), anomalies.get(0).segmentEndUtc());

        // 缺点断段：12、13 时与 08-10 时段之间缺 11 时 → 新段长 2，不超
        report("sg-2",
                item("2026-09-26T12:00:00Z", "6", true, "20"),
                item("2026-09-26T13:00:00Z", "6", true, "20"));
        assertEquals(1, service.listAnomalies(CODE).size());

        // 跨许可断段：升级许可版本后，14、15 时与 12、13 时虽相邻但版本不同 → 断段，均不超
        permit(1, "2026-09-26T00:00:00Z", "2026-09-27T00:00:00Z", "5", 2);
        report("sg-3",
                item("2026-09-26T14:00:00Z", "6", true, "20"),
                item("2026-09-26T15:00:00Z", "6", true, "20"));
        assertEquals(2, jdbc.queryForObject(
                "SELECT permit_version FROM overflow_hour WHERE outlet_code = ? AND hour_utc = ?",
                Integer.class, CODE, java.sql.Timestamp.valueOf("2026-09-26 14:00:00")));
        assertEquals(1, service.listAnomalies(CODE).size(), "跨许可断段后各段长 2，不新增异常");
    }

    // ---------------- 幂等 ----------------

    @Test
    void sameRequestIdReplaysAndDifferentParamsConflictAndFailureReleasesKey() {
        permit(0, "2026-09-26T08:00:00Z", "2026-09-26T12:00:00Z", "5", 3);
        HourReportResult first = report("idem-1", item("2026-09-26T09:00:00Z", "6", true, "20"));
        HourReportResult replay = report("idem-1", item("2026-09-26T09:00:00Z", "6", true, "20"));
        assertEquals(first.reported(), replay.reported());
        assertEquals(first.hours().get(0).markStatus(), replay.hours().get(0).markStatus());
        assertEquals(1, count("overflow_hour"), "同键同参回放首次冻结结果，不重复写入");

        // 同键异参 → 409，且不改变状态
        assertThrows(ConflictException.class,
                () -> report("idem-1", item("2026-09-26T10:00:00Z", "6", true, "20")));
        assertEquals(1, count("overflow_hour"));

        // 失败不占键：先以异参触发 422，再同键合法参数可成功
        assertThrows(BusinessException.class,
                () -> report("idem-2", item("2026-09-26T10:00:00Z", "1", true, "20")));
        HourReportResult afterFail = report("idem-2", item("2026-09-26T10:00:00Z", "6", true, "20"));
        assertEquals(1, afterFail.reported());
        assertEquals(2, count("overflow_hour"));
    }

    // ---------------- 溢流不改变原超标链与限排令 ----------------

    @Test
    void overflowEvidenceKeepsOriginalExceedanceChainAndRestrictionOrder() {
        // 原超标链：连续 3 次小时超标触发限排令
        cemsService.reportHourly(new HourlyReadingRequest(
                CODE, LocalDateTime.of(2026, 9, 26, 8, 0), new BigDecimal("11")));
        cemsService.reportHourly(new HourlyReadingRequest(
                CODE, LocalDateTime.of(2026, 9, 26, 9, 0), new BigDecimal("12")));
        HourlyReadingResult third = cemsService.reportHourly(new HourlyReadingRequest(
                CODE, LocalDateTime.of(2026, 9, 26, 10, 0), new BigDecimal("13")));
        assertTrue(third.restrictionTriggered());
        String orderNo = third.orderNo();

        // 雨季溢流证据：合规溢流标记
        permit(0, "2026-09-26T00:00:00Z", "2026-09-27T00:00:00Z", "5", 10);
        report("kp-1", item("2026-09-26T08:00:00Z", "6", true, "11"));

        // 原排放浓度与超标链永远保留；溢流不取消已生效限排令
        assertEquals(3, count("exceedance_mark"));
        assertEquals(3, count("monitor_reading"));
        assertEquals("PENDING_CONFIRM", cemsService.getOrder(orderNo).status());
        assertEquals("RESTRICTED", cemsService.getOutlet(CODE).status());

        // 溢流不减轻原超标判定：日均评价仍按原限值判超标
        var daily = cemsService.evaluateDaily(new DailyEvalRequest(
                "kp-daily", CODE, DAY, new BigDecimal("60")));
        assertTrue(daily.exceeded());
        assertEquals(4, count("exceedance_mark"));
    }
}
