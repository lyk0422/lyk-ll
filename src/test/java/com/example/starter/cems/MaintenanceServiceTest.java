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
import com.example.starter.cems.MaintenanceTypes.EffectiveAveragesView;
import com.example.starter.cems.MaintenanceTypes.EffectiveHourView;
import com.example.starter.cems.MaintenanceTypes.LabReportRequest;
import com.example.starter.cems.MaintenanceTypes.LabReportView;
import com.example.starter.cems.MaintenanceTypes.MaintenanceAdjustmentView;
import com.example.starter.cems.MaintenanceTypes.MaintenancePermitRequest;
import com.example.starter.cems.MaintenanceTypes.MaintenancePermitView;
import com.example.starter.cems.MaintenanceTypes.MaintenanceReviewRequest;
import com.example.starter.cems.MaintenanceTypes.MaintenanceReviewView;
import com.example.starter.cems.MaintenanceTypes.ReplacementConfirmRequest;
import com.example.starter.cems.MaintenanceTypes.ReplacementSubmitRequest;
import com.example.starter.cems.MaintenanceTypes.ReplacementView;
import com.example.starter.cems.MaintenanceTypes.SubstituteExceedanceView;

/**
 * 检修替代证据与有效监测统计主流程及失败分支测试（真实 H2，MODE=MySQL）：
 * 双人确认许可、整小时落窗、同排口检验报告（跨小时/跨排口/未知拒绝）、有限非负浓度（缺报告不按零填补）、
 * 另一人复核生效、同小时唯一当前版本、来源优先（机测优先不双计）、空集合无结果、
 * 封账冻结与封账后独立调差、替代超标独立来源（不撤销旧限排/缺点/原超标链）、封账前补机测失效替代、
 * 以及幂等回放/异参 409/失败不占键。
 */
@SpringBootTest
class MaintenanceServiceTest {

    private static final String CODE = "OUT-MT";
    private static final LocalDate DAY = LocalDate.of(2026, 9, 26);
    private static final String OTHER = "OUT-MT2";

    @Autowired
    private MaintenanceService service;
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
        CemsTestSupport.register(cemsService, OTHER, "50", "10");
    }

    private static Instant utc(String text) {
        return Instant.parse(text);
    }

    private MaintenancePermitView permit(String requestId, int expectedVersion, String start, String end) {
        return service.upsertPermit(new MaintenancePermitRequest(
                requestId, CODE, expectedVersion, utc(start), utc(end), "zhang", "wang"));
    }

    private MaintenancePermitView permitDay() {
        return permit("mp-1", 0, "2026-09-26T00:00:00Z", "2026-09-27T00:00:00Z");
    }

    private LabReportView report(String requestId, String code, String reportNo, String hourUtc) {
        Instant h = utc(hourUtc);
        return service.registerLabReport(new LabReportRequest(
                requestId, code, reportNo, h, h.plusSeconds(3600)));
    }

    private ReplacementView submit(String requestId, String hourUtc, String concentration,
                                   String reportNo, String submitter, int expectedVersion) {
        return service.submitReplacement(new ReplacementSubmitRequest(
                requestId, CODE, utc(hourUtc), new BigDecimal(concentration),
                reportNo, submitter, expectedVersion));
    }

    private ReplacementView confirm(String requestId, String hourUtc, String reviewer) {
        return service.confirmReplacement(new ReplacementConfirmRequest(requestId, CODE, utc(hourUtc), reviewer));
    }

    /** 提交并由另一人复核，一步得到生效替代。 */
    private ReplacementView submitAndConfirm(String hourUtc, String concentration, int expectedVersion,
                                             String submitReqId, String confirmReqId) {
        report("lr-" + hourUtc, CODE, "RPT-" + hourUtc, hourUtc);
        submit(submitReqId, hourUtc, concentration, "RPT-" + hourUtc, "li", expectedVersion);
        return confirm(confirmReqId, hourUtc, "zhao");
    }

    private void machineHour(String hourUtc, String value) {
        Instant h = utc(hourUtc);
        cemsService.reportHourly(new HourlyReadingRequest(
                CODE, LocalDateTime.ofInstant(h, java.time.ZoneOffset.UTC), new BigDecimal(value)));
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

    private String replacementStatus(String hourUtc, int version) {
        return jdbc.queryForObject(
                "SELECT status FROM replacement_hour WHERE outlet_code = ? AND hour_utc = ? AND version = ?",
                String.class, CODE, java.sql.Timestamp.valueOf("2026-09-26 "
                        + hourUtc.substring(11, 16) + ":00"), version);
    }

    // ---------------- 检修许可：双人确认 + 版本 ----------------

    @Test
    void permitRequiresTwoDistinctConfirmersAndVersionStartsAtOne() {
        MaintenancePermitView v1 = permitDay();
        assertEquals(1, v1.version());
        assertEquals("ACTIVE", v1.status());
        assertEquals("zhang", v1.confirmerA());
        assertEquals("wang", v1.confirmerB());

        // 两名不同人员：同一人确认拒绝（400）
        assertThrows(IllegalArgumentException.class, () -> service.upsertPermit(
                new MaintenancePermitRequest("mp-same", CODE, 1,
                        utc("2026-09-26T00:00:00Z"), utc("2026-09-28T00:00:00Z"), "li", "li")));
        // expectedVersion 不匹配 → 409
        assertThrows(ConflictException.class, () -> permit("mp-old", 0,
                "2026-09-26T00:00:00Z", "2026-09-28T00:00:00Z"));
        // 未知排口 → 404
        assertThrows(NotFoundException.class, () -> service.upsertPermit(
                new MaintenancePermitRequest("mp-404", "NO-SUCH", 0,
                        utc("2026-09-26T00:00:00Z"), utc("2026-09-27T00:00:00Z"), "a", "b")));
        // 半开窗口终点必须晚于起点 → 400
        assertThrows(IllegalArgumentException.class, () -> service.upsertPermit(
                new MaintenancePermitRequest("mp-win", CODE, 1,
                        utc("2026-09-26T12:00:00Z"), utc("2026-09-26T12:00:00Z"), "a", "b")));

        // 成功变更加 1，旧版本 SUPERSEDED
        MaintenancePermitView v2 = permit("mp-2", 1, "2026-09-26T00:00:00Z", "2026-09-28T00:00:00Z");
        assertEquals(2, v2.version());
        assertEquals("SUPERSEDED", jdbc.queryForObject(
                "SELECT status FROM maintenance_permit WHERE outlet_code = ? AND version = 1",
                String.class, CODE));
        assertEquals(2, count("maintenance_permit"));
    }

    // ---------------- 整小时落窗 ----------------

    @Test
    void replacementHourMustFullyFitInsideMaintenanceWindow() {
        // 窗口 08:30~10:30：替代小时必须整小时完整落窗
        permit("mw-1", 0, "2026-09-26T08:30:00Z", "2026-09-26T10:30:00Z");
        report("mw-r1", CODE, "RPT-MW-8", "2026-09-26T08:00:00Z");
        report("mw-r2", CODE, "RPT-MW-9", "2026-09-26T09:00:00Z");
        // [08:00,09:00) 越窗 → 422
        assertThrows(BusinessException.class, () -> submit("mw-s8", "2026-09-26T08:00:00Z",
                "20", "RPT-MW-8", "li", 0));
        // [09:00,10:00) 完整落窗，可提交（待复核）
        ReplacementView v = submit("mw-s9", "2026-09-26T09:00:00Z", "20", "RPT-MW-9", "li", 0);
        assertEquals("PENDING", v.status());
        // 无现行许可的排口不能提交 → 422
        assertThrows(BusinessException.class, () -> service.submitReplacement(
                new ReplacementSubmitRequest("mw-nopermit", OTHER, utc("2026-09-26T09:00:00Z"),
                        new BigDecimal("20"), "X", "li", 0)));
    }

    // ---------------- 检验报告：同排口、窗口精确一致 ----------------

    @Test
    void labReportMustMatchOutletAndExactHourWindow() {
        permitDay();
        report("lr-ok", CODE, "RPT-OK", "2026-09-26T09:00:00Z");
        // 未知报告 → 404
        assertThrows(NotFoundException.class, () -> submit("lr-404", "2026-09-26T09:00:00Z",
                "20", "RPT-NOPE", "li", 0));
        // 跨排口报告拒绝 → 422
        report("lr-other", OTHER, "RPT-OTHER", "2026-09-26T09:00:00Z");
        assertThrows(BusinessException.class, () -> submit("lr-cross", "2026-09-26T09:00:00Z",
                "20", "RPT-OTHER", "li", 0));
        // 跨小时报告（窗口 [08:00,10:00)）不能用于替代 09 时 → 422
        service.registerLabReport(new LabReportRequest("lr-span", CODE, "RPT-SPAN",
                utc("2026-09-26T08:00:00Z"), utc("2026-09-26T10:00:00Z")));
        assertThrows(BusinessException.class, () -> submit("lr-span-sub", "2026-09-26T09:00:00Z",
                "20", "RPT-SPAN", "li", 0));
        // 报告窗口非法 → 400
        assertThrows(IllegalArgumentException.class, () -> service.registerLabReport(
                new LabReportRequest("lr-bad", CODE, "RPT-BAD",
                        utc("2026-09-26T09:00:00Z"), utc("2026-09-26T09:00:00Z"))));
        assertEquals(0, count("replacement_hour"), "证据不合法不得写入替代");
    }

    @Test
    void reportDuplicateConflict() {
        permitDay();
        report("lr-d1", CODE, "RPT-DUP", "2026-09-26T09:00:00Z");
        // 同报告号重复 → 409
        assertThrows(ConflictException.class, () ->
                report("lr-d2", CODE, "RPT-DUP", "2026-09-26T09:00:00Z"));
    }

    // ---------------- 有限非负浓度；缺报告不能按零填补 ----------------

    @Test
    void concentrationMustBeFiniteNonNegativeAndReportRequired() {
        permitDay();
        report("cn-r", CODE, "RPT-CN", "2026-09-26T09:00:00Z");
        // null 浓度（缺报告不能按零填补的对称：无值不受理）→ 400
        assertThrows(IllegalArgumentException.class, () -> service.submitReplacement(
                new ReplacementSubmitRequest("cn-null", CODE, utc("2026-09-26T09:00:00Z"),
                        null, "RPT-CN", "li", 0)));
        // 负浓度 → 400
        assertThrows(IllegalArgumentException.class, () -> submit("cn-neg", "2026-09-26T09:00:00Z",
                "-0.1", "RPT-CN", "li", 0));
        // 非整点 → 400
        assertThrows(IllegalArgumentException.class, () -> submit("cn-half", "2026-09-26T09:30:00Z",
                "20", "RPT-CN", "li", 0));
        // 零浓度合法（有限非负，恰为零允许），但仍需报告
        ReplacementView zero = submit("cn-zero", "2026-09-26T09:00:00Z", "0", "RPT-CN", "li", 0);
        assertEquals(0, new BigDecimal("0").compareTo(zero.concentration()));
    }

    // ---------------- 另一人复核生效；同小时唯一当前版本 ----------------

    @Test
    void replacementEffectiveOnlyAfterDifferentReviewerAndSingleActiveVersion() {
        permitDay();
        report("rv-r9", CODE, "RPT-9", "2026-09-26T09:00:00Z");
        ReplacementView pending = submit("rv-s1", "2026-09-26T09:00:00Z", "20", "RPT-9", "li", 0);
        assertEquals(1, pending.version());
        assertEquals(0, pending.expectedVersion());
        assertEquals("PENDING", pending.status());
        assertNull(pending.reviewer());

        // 复核人=提交人 → 422
        assertThrows(BusinessException.class, () -> confirm("rv-cbad", "2026-09-26T09:00:00Z", "li"));
        // 另一人复核 → EFFECTIVE
        ReplacementView effective = confirm("rv-c1", "2026-09-26T09:00:00Z", "zhao");
        assertEquals("EFFECTIVE", effective.status());
        assertEquals("zhao", effective.reviewer());
        assertNotNull(effective.confirmedAt());
        // 无待复核版本时再复核 → 404
        assertThrows(NotFoundException.class, () -> confirm("rv-c2", "2026-09-26T09:00:00Z", "zhao"));

        // 新版本：提交 v2（expectedVersion=1）后，存在 PENDING 期间不能并发再提 → 409
        report("rv-r9b", CODE, "RPT-9B", "2026-09-26T09:00:00Z");
        submit("rv-s2", "2026-09-26T09:00:00Z", "22", "RPT-9B", "li", 1);
        assertThrows(ConflictException.class, () -> submit("rv-s2b", "2026-09-26T09:00:00Z",
                "23", "RPT-9B", "li", 1));
        // expectedVersion 错误 → 409
        assertThrows(ConflictException.class, () -> submit("rv-s2c", "2026-09-26T09:00:00Z",
                "23", "RPT-9B", "li", 0));
        // 复核 v2 生效，v1 SUPERSEDED；同小时仅一个当前有效
        confirm("rv-c3", "2026-09-26T09:00:00Z", "zhao");
        assertEquals("SUPERSEDED", replacementStatus("2026-09-26T09:00:00Z", 1));
        assertEquals("EFFECTIVE", replacementStatus("2026-09-26T09:00:00Z", 2));
        assertEquals(1L, jdbc.queryForObject(
                "SELECT COUNT(*) FROM replacement_hour WHERE outlet_code = ? AND status = 'EFFECTIVE'",
                Long.class, CODE));
    }

    // ---------------- 来源优先：机测优先，不双计 ----------------

    @Test
    void effectiveAveragesPreferMachineAndDoNotDoubleCount() {
        permitDay();
        // 08 时合法机测 12；09 时有效替代 20
        machineHour("2026-09-26T08:00:00Z", "12");
        submitAndConfirm("2026-09-26T09:00:00Z", "20", 0, "ep-s9", "ep-c9");

        EffectiveAveragesView view = service.effectiveAverages(CODE,
                utc("2026-09-26T08:00:00Z"), utc("2026-09-26T10:00:00Z"));
        assertEquals(2, view.totalSlots());
        assertEquals(2, view.countedCount(), "机测与替代各计一次，不双计");
        assertEquals(0, view.missingCount());
        assertBd("16", view.effectiveAvg());
        EffectiveHourView h8 = view.hours().get(0);
        EffectiveHourView h9 = view.hours().get(1);
        assertEquals("MACHINE", h8.source());
        assertNull(h8.replacementVersion());
        assertEquals("REPLACEMENT", h9.source());
        assertEquals(1, h9.replacementVersion());
        assertEquals("RPT-2026-09-26T09:00:00Z", h9.reportNo());
    }

    @Test
    void emptyCollectionReturnsNoResultInsteadOfZero() {
        permitDay();
        EffectiveAveragesView view = service.effectiveAverages(CODE,
                utc("2027-01-01T00:00:00Z"), utc("2027-01-01T03:00:00Z"));
        assertEquals(3, view.totalSlots());
        assertEquals(0, view.countedCount());
        assertEquals(3, view.missingCount());
        assertNull(view.effectiveAvg(), "空集合返回无结果而非零");
        assertTrue(view.hours().isEmpty());
    }

    @Test
    void pendingReplacementIsStillMissingAndNotCounted() {
        permitDay();
        report("pd-r", CODE, "RPT-PD", "2026-09-26T09:00:00Z");
        submit("pd-s", "2026-09-26T09:00:00Z", "20", "RPT-PD", "li", 0);
        EffectiveAveragesView view = service.effectiveAverages(CODE,
                utc("2026-09-26T09:00:00Z"), utc("2026-09-26T10:00:00Z"));
        assertEquals(0, view.countedCount(), "待复核替代尚无效，不计入有效口径");
        assertEquals(1, view.missingCount());
        assertNull(view.effectiveAvg());
    }

    // ---------------- 封账前补到合法机测：重算新口径并失效被替代来源 ----------------

    @Test
    void machineBackfillBeforeFreezeRecomputesAndInvalidatesReplacement() {
        permitDay();
        submitAndConfirm("2026-09-26T09:00:00Z", "15", 0, "bf-s9", "bf-c9");
        // 先由替代 15 计入，且产生独立替代超标
        assertEquals(1, service.listSubstituteExceedances(CODE).size());
        assertEquals("ACTIVE", service.listSubstituteExceedances(CODE).get(0).status());

        // 补到合法机测 30：机测优先，替代失效，替代超标来源失效（历史留证）
        machineHour("2026-09-26T09:00:00Z", "30");
        assertEquals("INVALIDATED", replacementStatus("2026-09-26T09:00:00Z", 1));
        EffectiveAveragesView view = service.effectiveAverages(CODE,
                utc("2026-09-26T09:00:00Z"), utc("2026-09-26T10:00:00Z"));
        assertEquals(1, view.countedCount());
        assertEquals("MACHINE", view.hours().get(0).source());
        assertBd("30", view.effectiveAvg());
        List<SubstituteExceedanceView> marks = service.listSubstituteExceedances(CODE);
        assertEquals(1, marks.size(), "替代超标历史只留证不删除");
        assertEquals("INVALIDATED", marks.get(0).status());
        assertEquals(0, count("maintenance_adjustment"), "封账前补机测不产生独立调差");

        // 已有机测后不能再提替代（机测优先）→ 422
        assertThrows(BusinessException.class,
                () -> submit("bf-s-again", "2026-09-26T09:00:00Z", "20", "RPT-2026-09-26T09:00:00Z", "li", 1));
    }

    // ---------------- 替代超标独立来源：不撤销旧限排/缺点/原超标链 ----------------

    @Test
    void substituteExceedanceIsIndependentAndNeverDeletesHistory() {
        permitDay();
        // 替代浓度 15 > 原小时限值 10：仅追加独立来源
        submitAndConfirm("2026-09-26T08:00:00Z", "15", 0, "se-s1", "se-c1");
        List<SubstituteExceedanceView> marks = service.listSubstituteExceedances(CODE);
        assertEquals(1, marks.size());
        assertEquals("ACTIVE", marks.get(0).status());
        assertBd("10", marks.get(0).limitValue());
        assertBd("15", marks.get(0).actualValue());
        assertEquals(1, marks.get(0).replacementVersion());
        // 不触碰原超标链、缺点告警与限排令
        assertEquals(0, count("exceedance_mark"));
        assertEquals(0, count("restriction_order"));
        assertEquals(0, count("data_gap"));

        // 新版本合规（5）：旧超标来源 SUPERSEDED 留证，不新增
        report("se-r2", CODE, "RPT-SE-8B", "2026-09-26T08:00:00Z");
        submit("se-s2", "2026-09-26T08:00:00Z", "5", "RPT-SE-8B", "li", 1);
        confirm("se-c2", "2026-09-26T08:00:00Z", "zhao");
        marks = service.listSubstituteExceedances(CODE);
        assertEquals(1, marks.size(), "合规替代不追加超标");
        assertEquals("SUPERSEDED", marks.get(0).status());
        assertEquals(2, jdbc.queryForObject(
                "SELECT COUNT(*) FROM replacement_hour WHERE outlet_code = ? AND hour_utc = ?",
                Long.class, CODE, java.sql.Timestamp.valueOf("2026-09-26 08:00:00")));

        // 替代复核记录链查询：同小时两版本（v1 SUPERSEDED、v2 EFFECTIVE）
        List<ReplacementView> chain = service.listReplacements(CODE,
                utc("2026-09-26T08:00:00Z"), utc("2026-09-26T09:00:00Z"));
        assertEquals(2, chain.size());
        assertEquals(1, chain.get(0).version());
        assertEquals("SUPERSEDED", chain.get(0).status());
        assertEquals(2, chain.get(1).version());
        assertEquals("EFFECTIVE", chain.get(1).status());
    }

    // ---------------- 封账冻结与封账后独立调差 ----------------

    @Test
    void freezeSnapshotsSelectionAndPostFreezeChangesOnlyAdjust() {
        permitDay();
        machineHour("2026-09-26T08:00:00Z", "12");
        submitAndConfirm("2026-09-26T09:00:00Z", "20", 0, "fz-s9", "fz-c9");

        MaintenanceReviewView frozen = service.freezeReview(
                new MaintenanceReviewRequest("fz-1", CODE, DAY));
        assertEquals("FROZEN", frozen.status());
        assertEquals(1, frozen.permitVersion());
        assertBd("16", frozen.effectiveAvg());
        assertEquals(2, frozen.countedCount());
        assertEquals(22, frozen.missingCount());
        assertEquals(2, frozen.items().size());
        assertEquals("MACHINE", frozen.items().get(0).source());
        assertEquals("REPLACEMENT", frozen.items().get(1).source());
        assertEquals(1, frozen.items().get(1).replacementVersion());

        // 重复封账 → 409
        assertThrows(ConflictException.class,
                () -> service.freezeReview(new MaintenanceReviewRequest("fz-2", CODE, DAY)));

        // 封账后补机测到原缺点 10 时：仅独立调差，冻结均值不变
        machineHour("2026-09-26T10:00:00Z", "30");
        // 封账后更正替代（09 时 20→40，新版本复核）：仅独立调差，冻结均值不变
        report("fz-r9b", CODE, "RPT-FZ-9B", "2026-09-26T09:00:00Z");
        submit("fz-s9b", "2026-09-26T09:00:00Z", "40", "RPT-FZ-9B", "li", 1);
        confirm("fz-c9b", "2026-09-26T09:00:00Z", "zhao");

        MaintenanceReviewView after = service.getReview(CODE, DAY);
        assertBd("16", after.effectiveAvg());
        assertEquals(2, after.countedCount(), "冻结分母不被调差重写");
        assertEquals(22, after.missingCount());
        assertEquals(2, after.items().size(), "冻结逐小时选择不被重写");

        List<MaintenanceAdjustmentView> adjustments = service.listAdjustments(CODE, DAY);
        assertEquals(2, adjustments.size());
        MaintenanceAdjustmentView backfill = adjustments.get(0);
        assertEquals("MACHINE_BACKFILL", backfill.changeType());
        assertNull(backfill.oldSource(), "原缺点旧来源为空");
        assertNull(backfill.oldValue());
        assertEquals("MACHINE", backfill.newSource());
        assertBd("30", backfill.newValue());
        MaintenanceAdjustmentView correction = adjustments.get(1);
        assertEquals("REPLACEMENT_CORRECTION", correction.changeType());
        assertEquals("REPLACEMENT", correction.oldSource());
        assertBd("20", correction.oldValue());
        assertEquals("REPLACEMENT", correction.newSource());
        assertBd("40", correction.newValue());
        assertEquals(1, correction.replacementVersion(), "调差记录被替代的旧版本 v1");

        // 实时有效口径按当前选择重算（不读冻结）：08 机测12、09 替代40、10 机测30
        EffectiveAveragesView live = service.effectiveAverages(CODE,
                utc("2026-09-26T08:00:00Z"), utc("2026-09-26T11:00:00Z"));
        assertEquals(3, live.countedCount());
        assertEquals(0, live.missingCount());
        assertBd("27.3333", live.effectiveAvg());
        assertEquals(2, live.hours().get(1).replacementVersion(), "实时口径使用更正后的替代 v2");
    }

    // ---------------- 幂等：回放 / 异参 409 / 失败不占键 ----------------

    @Test
    void idempotencyReplaysConflictsAndFailureReleasesKey() {
        permitDay();
        report("id-r9", CODE, "RPT-ID", "2026-09-26T09:00:00Z");
        ReplacementSubmitRequest req = new ReplacementSubmitRequest(
                "id-s1", CODE, utc("2026-09-26T09:00:00Z"), new BigDecimal("20"), "RPT-ID", "li", 0);
        ReplacementView first = service.submitReplacement(req);
        ReplacementView replay = service.submitReplacement(req);
        assertEquals(first.version(), replay.version());
        assertEquals(first.status(), replay.status());
        assertEquals(1, count("replacement_hour"), "同键同参回放，不重复写入");

        // 同键异参 → 409
        assertThrows(ConflictException.class, () -> service.submitReplacement(
                new ReplacementSubmitRequest("id-s1", CODE, utc("2026-09-26T09:00:00Z"),
                        new BigDecimal("21"), "RPT-ID", "li", 0)));
        assertEquals(1, count("replacement_hour"));

        // 失败不占键：先以负浓度触发 400（同事务回滚），再同键用合法参数在空闲小时 11 时成功
        report("id-r11", CODE, "RPT-ID-11", "2026-09-26T11:00:00Z");
        assertThrows(IllegalArgumentException.class, () -> service.submitReplacement(
                new ReplacementSubmitRequest("id-s2", CODE, utc("2026-09-26T11:00:00Z"),
                        new BigDecimal("-5"), "RPT-ID-11", "li", 0)));
        ReplacementView afterFail = service.submitReplacement(
                new ReplacementSubmitRequest("id-s2", CODE, utc("2026-09-26T11:00:00Z"),
                        new BigDecimal("20"), "RPT-ID-11", "li", 0));
        assertEquals(1, afterFail.version());
        assertEquals("PENDING", afterFail.status());
        assertEquals(2, count("replacement_hour"));
    }

    @Test
    void idempotentConfirmReplays() {
        permitDay();
        report("ic-r", CODE, "RPT-IC", "2026-09-26T09:00:00Z");
        submit("ic-s", "2026-09-26T09:00:00Z", "20", "RPT-IC", "li", 0);
        ReplacementView c1 = confirm("ic-c", "2026-09-26T09:00:00Z", "zhao");
        ReplacementView c2 = confirm("ic-c", "2026-09-26T09:00:00Z", "zhao");
        assertEquals(c1.status(), c2.status());
        assertEquals(c1.reviewer(), c2.reviewer());
        assertEquals(1L, jdbc.queryForObject(
                "SELECT COUNT(*) FROM replacement_hour WHERE outlet_code = ? AND hour_utc = ?",
                Long.class, CODE, java.sql.Timestamp.valueOf("2026-09-26 09:00:00")));
    }
}
