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

import com.example.starter.cems.CemsTypes.HourlyReadingRequest;
import com.example.starter.cems.SubstituteTypes.AdjustmentView;
import com.example.starter.cems.SubstituteTypes.EffectiveAveragesView;
import com.example.starter.cems.SubstituteTypes.FreezeRequest;
import com.example.starter.cems.SubstituteTypes.FreezeView;
import com.example.starter.cems.SubstituteTypes.LabReportRequest;
import com.example.starter.cems.SubstituteTypes.PermitConfirmRequest;
import com.example.starter.cems.SubstituteTypes.PermitUpsertRequest;
import com.example.starter.cems.SubstituteTypes.PermitView;
import com.example.starter.cems.SubstituteTypes.SubstituteExceedanceView;
import com.example.starter.cems.SubstituteTypes.SubstituteReviewRequest;
import com.example.starter.cems.SubstituteTypes.SubstituteReviewResult;
import com.example.starter.cems.SubstituteTypes.SubstituteSubmitRequest;
import com.example.starter.cems.SubstituteTypes.SubstituteSubmitResult;
import com.example.starter.cems.SubstituteTypes.SubstituteView;

/**
 * 检修替代监测与有效监测统计业务测试（H2 MODE=MySQL 真实事务）：
 * 许可两人确认、整小时落窗、报告资格、替代复核、来源优先、空集合、
 * 封账前补机测失效、封账后独立调差、版本与幂等边界。
 */
@SpringBootTest
class SubstituteServiceTest {

    private static final String CODE = "OUT-SUB";
    private static final LocalDate DAY = LocalDate.of(2026, 9, 26);
    private static final Instant H08 = Instant.parse("2026-09-26T08:00:00Z");
    private static final Instant H09 = Instant.parse("2026-09-26T09:00:00Z");

    @Autowired
    private SubstituteService service;
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

    /** UTC 整点对应的 Asia/Shanghai 本地整点（机测读数入口用本地时间）。 */
    private static LocalDateTime localHour(Instant utcHour) {
        return LocalDateTime.ofInstant(utcHour, CemsClock.ZONE);
    }

    private void machineReading(Instant utcHour, String value) {
        cemsService.reportHourly(new HourlyReadingRequest(
                CODE, localHour(utcHour), new BigDecimal(value)));
    }

    private void activePermit() {
        service.upsertPermit(new PermitUpsertRequest("p-up", CODE, 0,
                utc("2026-09-26T00:00:00Z"), utc("2026-09-27T00:00:00Z"), "creator"));
        service.confirmPermit(new PermitConfirmRequest("p-c1", CODE, 1, "confirmerA"));
        service.confirmPermit(new PermitConfirmRequest("p-c2", CODE, 1, "confirmerB"));
    }

    private void report(String reportNo, Instant hourUtc) {
        service.registerReport(new LabReportRequest("r-" + reportNo, reportNo, CODE, hourUtc));
    }

    private SubstituteSubmitResult submit(String requestId, Instant hourUtc, String value,
                                          String reportNo, int expectedVersion) {
        return service.submitSubstitute(new SubstituteSubmitRequest(
                requestId, CODE, hourUtc, new BigDecimal(value), reportNo, "submitter", expectedVersion));
    }

    // ---------------- 检修许可：两名不同人员确认 ----------------

    @Test
    void permitRequiresTwoDistinctConfirmers() {
        PermitView created = service.upsertPermit(new PermitUpsertRequest("p1", CODE, 0,
                utc("2026-09-26T00:00:00Z"), utc("2026-09-27T00:00:00Z"), "creator"));
        assertEquals(1, created.version());
        assertEquals("PENDING_CONFIRM", created.status());

        PermitView afterFirst = service.confirmPermit(new PermitConfirmRequest("p1-c1", CODE, 1, "张三"));
        assertEquals("PENDING_CONFIRM", afterFirst.status(), "仅一名确认人不得生效");
        assertEquals(List.of("张三"), afterFirst.confirmers());

        BusinessException dup = assertThrows(BusinessException.class, () ->
                service.confirmPermit(new PermitConfirmRequest("p1-c2", CODE, 1, "张三")));
        assertTrue(dup.getMessage().contains("两名不同人员"));

        PermitView active = service.confirmPermit(new PermitConfirmRequest("p1-c3", CODE, 1, "李四"));
        assertEquals("ACTIVE", active.status());
        assertEquals(List.of("张三", "李四"), active.confirmers());

        assertThrows(ConflictException.class, () ->
                service.confirmPermit(new PermitConfirmRequest("p1-c4", CODE, 1, "王五")));
    }

    @Test
    void permitVersioningStartsAtOneAndStaleVersionConflicts() {
        service.upsertPermit(new PermitUpsertRequest("p2-a", CODE, 0,
                utc("2026-09-26T00:00:00Z"), utc("2026-09-27T00:00:00Z"), "creator"));
        ConflictException stale = assertThrows(ConflictException.class, () ->
                service.upsertPermit(new PermitUpsertRequest("p2-b", CODE, 0,
                        utc("2026-09-26T00:00:00Z"), utc("2026-09-27T00:00:00Z"), "creator")));
        assertTrue(stale.getMessage().contains("版本不匹配"));

        PermitView v2 = service.upsertPermit(new PermitUpsertRequest("p2-c", CODE, 1,
                utc("2026-09-26T06:00:00Z"), utc("2026-09-26T12:00:00Z"), "creator"));
        assertEquals(2, v2.version());
        assertEquals("PENDING_CONFIRM", v2.status(), "新版本须重新两人确认");
        assertEquals("SUPERSEDED", jdbc.queryForObject(
                "SELECT status FROM maintenance_permit WHERE outlet_code = ? AND version = 1",
                String.class, CODE));
    }

    // ---------------- 替代提交资格：整点、报告、落窗、缺机测 ----------------

    @Test
    void submitRejectsUnknownOutletCrossHourReportAndOutOfWindow() {
        activePermit();
        report("RPT-1", H08);
        // 未知排口 → 404
        assertThrows(NotFoundException.class, () ->
                service.submitSubstitute(new SubstituteSubmitRequest("s-x", "OUT-NONE", H08,
                        new BigDecimal("5"), "RPT-1", "submitter", 0)));
        // 非整点 → 400
        assertThrows(IllegalArgumentException.class, () ->
                submit("s-bad-hour", utc("2026-09-26T08:30:00Z"), "5", "RPT-1", 0));
        // 负浓度 → 400
        assertThrows(IllegalArgumentException.class, () ->
                submit("s-neg", H08, "-1", "RPT-1", 0));
        // 缺报告不能按零浓度填补 → 422
        BusinessException noReport = assertThrows(BusinessException.class, () ->
                submit("s-no-report", H09, "0", "RPT-MISSING", 0));
        assertTrue(noReport.getMessage().contains("不能按零浓度填补"));
        // 跨小时报告 → 422
        BusinessException crossHour = assertThrows(BusinessException.class, () ->
                submit("s-cross-hour", H09, "5", "RPT-1", 0));
        assertTrue(crossHour.getMessage().contains("跨小时"));
        // 越窗（窗口外小时）→ 422
        report("RPT-2", utc("2026-09-28T00:00:00Z"));
        BusinessException outOfWindow = assertThrows(BusinessException.class, () ->
                submit("s-out-window", utc("2026-09-28T00:00:00Z"), "5", "RPT-2", 0));
        assertTrue(outOfWindow.getMessage().contains("窗口"));
    }

    @Test
    void submitRejectsCrossOutletReportAndRequiresActivePermitAndMissingMachine() {
        CemsTestSupport.register(cemsService, "OUT-SUB2", "50", "10");
        report("RPT-A", H08);
        // 许可未两人确认生效 → 422
        service.upsertPermit(new PermitUpsertRequest("p3", CODE, 0,
                utc("2026-09-26T00:00:00Z"), utc("2026-09-27T00:00:00Z"), "creator"));
        BusinessException noActive = assertThrows(BusinessException.class, () ->
                submit("s-no-active", H08, "5", "RPT-A", 0));
        assertTrue(noActive.getMessage().contains("两名不同人员"));
        service.confirmPermit(new PermitConfirmRequest("p3-c1", CODE, 1, "张三"));
        service.confirmPermit(new PermitConfirmRequest("p3-c2", CODE, 1, "李四"));
        // 跨排口报告 → 422
        service.registerReport(new LabReportRequest("r-RPT-B", "RPT-B", "OUT-SUB2", H08));
        BusinessException crossOutlet = assertThrows(BusinessException.class, () ->
                submit("s-cross-outlet", H08, "5", "RPT-B", 0));
        assertTrue(crossOutlet.getMessage().contains("跨排口"));
        // 该小时已有合法机测 → 422
        machineReading(H08, "7");
        BusinessException hasMachine = assertThrows(BusinessException.class, () ->
                submit("s-has-machine", H08, "5", "RPT-A", 0));
        assertTrue(hasMachine.getMessage().contains("机测"));
    }

    // ---------------- 替代复核与版本 ----------------

    @Test
    void reviewRequiresDifferentReviewerAndOneCurrentVersionPerHour() {
        activePermit();
        report("RPT-1", H08);
        SubstituteSubmitResult submitted = submit("s1", H08, "8", "RPT-1", 0);
        assertEquals(1, submitted.version());
        assertEquals("PENDING_REVIEW", submitted.status());

        // 复核人不得等于提交人
        BusinessException samePerson = assertThrows(BusinessException.class, () ->
                service.reviewSubstitute(new SubstituteReviewRequest("rv-self", CODE, H08, 1, "submitter")));
        assertTrue(samePerson.getMessage().contains("不同于提交人"));

        SubstituteReviewResult reviewed = service.reviewSubstitute(
                new SubstituteReviewRequest("rv-1", CODE, H08, 1, "reviewer"));
        assertEquals("EFFECTIVE", reviewed.status());
        assertFalse(reviewed.exceedanceMarked(), "8 未超小时限值 10，不应追加超标来源");

        // 更正：expectedVersion 须为当前版本，成功加 1，旧版 SUPERSEDED
        ConflictException stale = assertThrows(ConflictException.class, () ->
                submit("s2-stale", H08, "9", "RPT-1", 0));
        assertTrue(stale.getMessage().contains("版本不匹配"));
        SubstituteSubmitResult corrected = submit("s2", H08, "9", "RPT-1", 1);
        assertEquals(2, corrected.version());
        List<SubstituteView> ledger = service.listSubstitutes(CODE);
        assertEquals(2, ledger.size());
        assertEquals("SUPERSEDED", ledger.get(0).status());
        assertEquals("PENDING_REVIEW", ledger.get(1).status());
        // 一小时最多一个当前版本：旧版复核拒绝
        assertThrows(ConflictException.class, () ->
                service.reviewSubstitute(new SubstituteReviewRequest("rv-old", CODE, H08, 1, "reviewer")));
    }

    // ---------------- 有效监测均值：来源优先、不双计、空集合 ----------------

    @Test
    void effectiveAveragePrefersMachineAndNeverDoubleCounts() {
        activePermit();
        report("RPT-1", H08);
        report("RPT-2", H09);
        // 08 机测 8；09 有效替代 12
        machineReading(H08, "8");
        submit("s1", H09, "12", "RPT-2", 0);
        service.reviewSubstitute(new SubstituteReviewRequest("rv-1", CODE, H09, 1, "reviewer"));

        EffectiveAveragesView view = service.averages(CODE,
                utc("2026-09-26T08:00:00Z"), utc("2026-09-26T11:00:00Z"));
        assertEquals(3, view.totalSlots());
        assertEquals(1, view.machineCount());
        assertEquals(1, view.substituteCount());
        assertEquals(1, view.missingCount());
        assertEquals(List.of(Instant.parse("2026-09-26T10:00:00Z")), view.missingHours());
        assertEquals(0, view.avgValue().compareTo(new BigDecimal("10.0000")),
                "两小时一机测一替代均值=(8+12)/2=10，同小时不双计");

        // 封账前补到 09 时合法机测 6：替代失效留证，新口径只算机测
        machineReading(H09, "6");
        EffectiveAveragesView after = service.averages(CODE,
                utc("2026-09-26T08:00:00Z"), utc("2026-09-26T11:00:00Z"));
        assertEquals(2, after.machineCount());
        assertEquals(0, after.substituteCount());
        assertEquals(0, after.avgValue().compareTo(new BigDecimal("7.0000")),
                "补机测后均值=(8+6)/2=7，被替代来源失效");
        List<SubstituteView> ledger = service.listSubstitutes(CODE);
        assertEquals("INVALIDATED", ledger.get(0).status(), "替代失效留证不删除");
    }

    @Test
    void effectiveAverageEmptySetReturnsNoResult() {
        EffectiveAveragesView view = service.averages(CODE,
                utc("2026-09-26T08:00:00Z"), utc("2026-09-26T10:00:00Z"));
        assertNull(view.avgValue(), "空集合返回无结果而非零");
        assertEquals(0, view.machineCount());
        assertEquals(0, view.substituteCount());
        assertEquals(2, view.missingCount());
        assertEquals(2, view.missingHours().size());
    }

    // ---------------- 替代超标独立来源：只留证、不撤销旧告警 ----------------

    @Test
    void substituteExceedanceAppendsIndependentSourceWithoutTouchingLegacy() {
        activePermit();
        report("RPT-1", H08);
        submit("s1", H08, "15", "RPT-1", 0);
        SubstituteReviewResult reviewed = service.reviewSubstitute(
                new SubstituteReviewRequest("rv-1", CODE, H08, 1, "reviewer"));
        assertTrue(reviewed.exceedanceMarked(), "替代 15 超小时限值 10 须追加独立超标来源");

        List<SubstituteExceedanceView> exceedances = service.listExceedances(CODE);
        assertEquals(1, exceedances.size());
        assertEquals("ACTIVE", exceedances.get(0).status());
        assertEquals(0, exceedances.get(0).limitValue().compareTo(new BigDecimal("10")));
        assertEquals(0, exceedances.get(0).actualValue().compareTo(new BigDecimal("15")));
        // 不自动产生限排令、不改原超标链与缺点告警
        assertEquals(0L, jdbc.queryForObject(
                "SELECT COUNT(*) FROM restriction_order WHERE outlet_code = ?", Long.class, CODE));
        assertEquals(0L, jdbc.queryForObject(
                "SELECT COUNT(*) FROM exceedance_mark WHERE outlet_code = ?", Long.class, CODE));

        // 封账前补机测：超标来源置 SUPERSEDED 留证不删除
        machineReading(H08, "6");
        List<SubstituteExceedanceView> after = service.listExceedances(CODE);
        assertEquals(1, after.size(), "超标历史只留证不能删除");
        assertEquals("SUPERSEDED", after.get(0).status());
    }

    // ---------------- 日结冻结与独立调差 ----------------

    @Test
    void freezeKeepsSnapshotAndPostFreezeChangesBecomeAdjustments() {
        activePermit();
        report("RPT-1", H08);
        report("RPT-2", H09);
        machineReading(H08, "8");
        submit("s1", H09, "12", "RPT-2", 0);
        service.reviewSubstitute(new SubstituteReviewRequest("rv-1", CODE, H09, 1, "reviewer"));

        FreezeView frozen = service.freeze(new FreezeRequest("f1", CODE, DAY));
        assertEquals("FROZEN", frozen.status());
        assertEquals(1, frozen.machineCount());
        assertEquals(1, frozen.substituteCount());
        assertEquals(22, frozen.missingCount());
        assertEquals(0, frozen.avgValue().compareTo(new BigDecimal("10.0000")));
        assertEquals(24, frozen.items().size());
        assertThrows(ConflictException.class, () ->
                service.freeze(new FreezeRequest("f1-dup", CODE, DAY)));

        // 冻结后补机测 09 时：仅独立调差，冻结均值不重写
        machineReading(H09, "6");
        // 冻结后更正替代：仅独立调差，台账版本不加
        SubstituteSubmitResult corrected = submit("s2", H09, "20", "RPT-2", 1);
        assertTrue(corrected.frozen());
        assertTrue(corrected.adjustmentRecorded());
        assertEquals(1, corrected.version(), "冻结后更正不加版本");

        FreezeView after = service.getFreeze(CODE, DAY);
        assertEquals(0, after.avgValue().compareTo(new BigDecimal("10.0000")), "冻结均值不重写");
        assertEquals(1, after.substituteCount());

        List<AdjustmentView> chain = service.listAdjustments(CODE, DAY);
        assertEquals(2, chain.size());
        assertEquals("MACHINE_SUPPLEMENT", chain.get(0).adjustType());
        assertEquals("SUBSTITUTE", chain.get(0).oldSource(), "冻结时 09 时来源为替代");
        assertEquals(0, chain.get(0).oldValue().compareTo(new BigDecimal("12")));
        assertEquals("MACHINE", chain.get(0).newSource());
        assertEquals(0, chain.get(0).newValue().compareTo(new BigDecimal("6")));
        assertEquals("SUBSTITUTE_CORRECT", chain.get(1).adjustType());
        assertEquals(0, chain.get(1).newValue().compareTo(new BigDecimal("20")));
    }

    // ---------------- 幂等：同参重放、异参 409、失败不占键 ----------------

    @Test
    void idempotencyReplaysSameParamsAndRejectsDifferentParams() {
        activePermit();
        report("RPT-1", H08);
        SubstituteSubmitResult first = submit("idem-1", H08, "8", "RPT-1", 0);
        SubstituteSubmitResult replay = submit("idem-1", H08, "8", "RPT-1", 0);
        assertEquals(first.version(), replay.version());
        assertEquals(1L, jdbc.queryForObject(
                "SELECT COUNT(*) FROM substitute_hour WHERE outlet_code = ?", Long.class, CODE));
        assertThrows(ConflictException.class, () ->
                submit("idem-1", H08, "9", "RPT-1", 0));
    }

    @Test
    void failedRequestDoesNotOccupyIdempotencyKey() {
        activePermit();
        // 缺报告失败 → 不占键；补登记报告后同键成功
        assertThrows(BusinessException.class, () ->
                submit("idem-fail", H08, "8", "RPT-LATE", 0));
        assertEquals(0L, jdbc.queryForObject(
                "SELECT COUNT(*) FROM idempotent_request WHERE request_id = 'idem-fail'", Long.class));
        report("RPT-LATE", H08);
        SubstituteSubmitResult ok = submit("idem-fail", H08, "8", "RPT-LATE", 0);
        assertEquals(1, ok.version());
        assertNotNull(service.getCurrentPermit(CODE));
    }
}
