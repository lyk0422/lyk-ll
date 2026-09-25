package com.example.starter.forestry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.LocalDate;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.example.starter.forestry.service.ForestryService.AcceptanceResult;
import com.example.starter.forestry.service.ForestryService.ApplicationResult;
import com.example.starter.forestry.service.ForestryService.ArchiveResult;
import com.example.starter.forestry.service.ForestryService.ConfirmResult;
import com.example.starter.forestry.service.ForestryService.LicenseResult;
import com.example.starter.forestry.support.ForestryException;
import com.example.starter.forestry.support.LicenseExpiredException;

/**
 * 采伐许可业务主流程与边界规则测试（真实 H2 库）。
 */
class ForestryServiceTest extends ForestryTestBase {

    // ---------- 限额恰达边界 ----------

    @Test
    @DisplayName("限额恰达：申请使累计恰达限额合法，超出整笔拒绝")
    void quotaExactBoundaryAtApplication() {
        givenCommercialPlot("P1", 30, 25, "PINE");
        givenCommercialPlot("P2", 30, 25, "PINE");
        givenQuota(2026, "PINE", "1000");

        // 先发证占用 600
        ApplicationResult first = service.submitApplication("app-1", "P1", "OP-1", 2026,
                new BigDecimal("600"));
        assertEquals("APPROVED", first.status());
        service.issueLicense("lic-1", first.applicationId());

        // 恰达限额：600 + 400 = 1000，合法
        ApplicationResult exact = service.submitApplication("app-2", "P2", "OP-1", 2026,
                new BigDecimal("400"));
        assertEquals("APPROVED", exact.status());

        // 超出：600 + 401 > 1000，整笔拒绝
        ApplicationResult exceeded = service.submitApplication("app-3", "P2", "OP-1", 2026,
                new BigDecimal("401"));
        assertEquals("REJECTED", exceeded.status());
        assertEquals("QUOTA_EXCEEDED", exceeded.rejectReason());
    }

    @Test
    @DisplayName("限额恰达：发证占用恰达限额成功，下一笔 409 且不占用")
    void quotaExactBoundaryAtIssuance() {
        givenCommercialPlot("P1", 30, 25, "PINE");
        givenCommercialPlot("P2", 30, 25, "PINE");
        givenQuota(2026, "PINE", "1000");

        ApplicationResult a1 = service.submitApplication("app-1", "P1", "OP-1", 2026,
                new BigDecimal("1000"));
        ApplicationResult a2 = service.submitApplication("app-2", "P2", "OP-1", 2026,
                new BigDecimal("1"));
        assertEquals("APPROVED", a1.status());

        LicenseResult license = service.issueLicense("lic-1", a1.applicationId());
        assertEquals("VALID", license.status());
        assertEquals(0, new BigDecimal("1000").compareTo(service.getQuota(2026, "PINE").usedVolume()));

        ForestryException ex = assertThrows(ForestryException.class,
                () -> service.issueLicense("lic-2", a2.applicationId()));
        assertEquals(409, ex.httpStatus());
        assertEquals("QUOTA_EXCEEDED", ex.code());
        // 未占用限额
        assertEquals(0, new BigDecimal("1000").compareTo(service.getQuota(2026, "PINE").usedVolume()));
    }

    // ---------- 禁伐与生态公益林拒绝 ----------

    @Test
    @DisplayName("生态公益林与禁伐区小班一律拒绝")
    void ecologicalAndNoHarvestZoneRejected() {
        givenEcologicalPlot("PE", "PINE");
        givenNoHarvestZonePlot("PN", "PINE");
        givenQuota(2026, "PINE", "1000");

        ApplicationResult eco = service.submitApplication("app-e", "PE", "OP-1", 2026,
                new BigDecimal("10"));
        assertEquals("REJECTED", eco.status());
        assertEquals("ECOLOGICAL_FOREST", eco.rejectReason());

        ApplicationResult noHarvest = service.submitApplication("app-n", "PN", "OP-1", 2026,
                new BigDecimal("10"));
        assertEquals("REJECTED", noHarvest.status());
        assertEquals("NO_HARVEST_ZONE", noHarvest.rejectReason());
    }

    // ---------- 成熟龄口径 ----------

    @Test
    @DisplayName("成熟龄口径：恰达成熟龄合法，低一年拒绝")
    void matureAgeBoundary() {
        givenCommercialPlot("PM", 25, 25, "PINE");
        givenCommercialPlot("PY", 24, 25, "PINE");
        givenQuota(2026, "PINE", "1000");

        ApplicationResult mature = service.submitApplication("app-m", "PM", "OP-1", 2026,
                new BigDecimal("10"));
        assertEquals("APPROVED", mature.status());

        ApplicationResult young = service.submitApplication("app-y", "PY", "OP-1", 2026,
                new BigDecimal("10"));
        assertEquals("REJECTED", young.status());
        assertEquals("STAND_NOT_MATURE", young.rejectReason());
    }

    // ---------- 90 日证件边界 ----------

    @Test
    @DisplayName("90 日证件边界：第 90 日确认有效，第 91 日作废且限额当日释放")
    void licenseNinetyDayBoundary() {
        givenCommercialPlot("P1", 30, 25, "PINE");
        givenCommercialPlot("P2", 30, 25, "PINE");
        givenQuota(2026, "PINE", "1000");

        // 2026-03-01 发证，最后有效日 = 2026-05-29（第 90 个自然日）
        ApplicationResult a1 = service.submitApplication("app-1", "P1", "OP-1", 2026,
                new BigDecimal("100"));
        LicenseResult l1 = service.issueLicense("lic-1", a1.applicationId());
        assertEquals(LocalDate.of(2026, 3, 1), l1.issueDate());
        assertEquals(LocalDate.of(2026, 5, 29), l1.expireDate());

        clock.setDate(LocalDate.of(2026, 5, 29));
        ConfirmResult confirmed = service.confirmHarvest(l1.licenseId());
        assertEquals("CONFIRMED", confirmed.status());
        assertEquals(LocalDate.of(2026, 5, 29), confirmed.confirmDate());
        assertNotNull(confirmed.regenerationTaskId());

        // 第二张证：第 91 日确认 → 作废 + 限额释放
        ApplicationResult a2 = service.submitApplication("app-2", "P2", "OP-1", 2026,
                new BigDecimal("200"));
        LicenseResult l2 = service.issueLicense("lic-2", a2.applicationId());
        assertEquals(0, new BigDecimal("300").compareTo(service.getQuota(2026, "PINE").usedVolume()));

        // l2 于 2026-05-29 签发，最后有效日 2026-08-26；推进到第 91 日
        clock.setDate(l2.expireDate().plusDays(1));
        LicenseExpiredException ex = assertThrows(LicenseExpiredException.class,
                () -> service.confirmHarvest(l2.licenseId()));
        assertEquals("LICENSE_EXPIRED", ex.code());
        assertEquals("VOID", service.getLicense(l2.licenseId()).status());
        // 限额当日释放：300 - 200 = 100
        assertEquals(0, new BigDecimal("100").compareTo(service.getQuota(2026, "PINE").usedVolume()));
    }

    // ---------- 更新验收 85% 边界 ----------

    @Test
    @DisplayName("验收合格率 85% 边界：恰达 85 合格，84.99 进入补植")
    void acceptanceEightyFiveBoundary() {
        givenCommercialPlot("P1", 30, 25, "PINE");
        givenCommercialPlot("P2", 30, 25, "PINE");
        givenQuota(2026, "PINE", "1000");

        long task1 = confirmAndGetTask("app-1", "lic-1", "P1");
        AcceptanceResult pass = service.submitAcceptance(task1, "acc-1", new BigDecimal("85.00"));
        assertEquals("PASSED", pass.result());
        assertEquals("PASSED", pass.taskStatus());

        long task2 = confirmAndGetTask("app-2", "lic-2", "P2");
        AcceptanceResult fail = service.submitAcceptance(task2, "acc-2", new BigDecimal("84.99"));
        assertEquals("FAILED", fail.result());
        assertEquals("REPLANT", fail.taskStatus());
        assertNotNull(fail.replantDeadline());
    }

    // ---------- 补植期限与限批联动 ----------

    @Test
    @DisplayName("补植期限：满 6 个月月末截止，截止日合格通过，逾期仍不合格列入限批")
    void replantDeadlineAndBlacklist() {
        givenCommercialPlot("P1", 30, 25, "PINE");
        givenCommercialPlot("P2", 30, 25, "PINE");
        givenQuota(2026, "PINE", "1000");

        // 任务一：2026-03-10 首轮不合格 → 补植截止 2026-09-30（满 6 个月月末）
        clock.setDate(LocalDate.of(2026, 3, 10));
        long task1 = confirmAndGetTask("app-1", "lic-1", "P1");
        AcceptanceResult first = service.submitAcceptance(task1, "acc-1", new BigDecimal("50"));
        assertEquals("REPLANT", first.taskStatus());
        assertEquals(LocalDate.of(2026, 9, 30), first.replantDeadline());

        // 恰在截止日补植验收合格
        clock.setDate(LocalDate.of(2026, 9, 30));
        AcceptanceResult replantPass = service.submitAcceptance(task1, "acc-2",
                new BigDecimal("90"));
        assertEquals("PASSED", replantPass.result());
        assertFalse(replantPass.blacklisted());

        // 任务二：补植逾期一天提交 → 不合格并列入限批名单
        clock.setDate(LocalDate.of(2026, 3, 10));
        long task2 = confirmAndGetTask("app-2", "lic-2", "P2");
        service.submitAcceptance(task2, "acc-3", new BigDecimal("50"));
        clock.setDate(LocalDate.of(2026, 10, 1));
        AcceptanceResult late = service.submitAcceptance(task2, "acc-4", new BigDecimal("99"));
        assertEquals("FAILED", late.result());
        assertTrue(late.blacklisted());

        // 限批联动：期限内新申请一律拒绝
        ApplicationResult rejected = service.submitApplication("app-x", "P1", "OP-1", 2026,
                new BigDecimal("10"));
        assertEquals("REJECTED", rejected.status());
        assertEquals("OPERATOR_BLACKLISTED", rejected.rejectReason());
    }

    @Test
    @DisplayName("限批期限：截止日当日仍限批，次日恢复")
    void blacklistDurationBoundary() {
        givenCommercialPlot("P1", 30, 25, "PINE");
        givenQuota(2026, "PINE", "1000");
        givenBlacklist("OP-B", LocalDate.of(2027, 3, 1));

        ApplicationResult banned = service.submitApplication("app-b", "P1", "OP-B", 2026,
                new BigDecimal("10"));
        assertEquals("REJECTED", banned.status());
        assertEquals("OPERATOR_BLACKLISTED", banned.rejectReason());

        clock.setDate(LocalDate.of(2027, 3, 2));
        ApplicationResult recovered = service.submitApplication("app-c", "P1", "OP-B", 2026,
                new BigDecimal("10"));
        assertEquals("APPROVED", recovered.status());
    }

    // ---------- 幂等 ----------

    @Test
    @DisplayName("同键重放：同参返回首次结果，异参 409 且不占用限额")
    void idempotentReplay() {
        givenCommercialPlot("P1", 30, 25, "PINE");
        givenQuota(2026, "PINE", "1000");

        ApplicationResult first = service.submitApplication("app-1", "P1", "OP-1", 2026,
                new BigDecimal("100"));
        ApplicationResult replay = service.submitApplication("app-1", "P1", "OP-1", 2026,
                new BigDecimal("100"));
        assertEquals(first.applicationId(), replay.applicationId());
        assertEquals(first.status(), replay.status());

        ForestryException conflict = assertThrows(ForestryException.class,
                () -> service.submitApplication("app-1", "P1", "OP-1", 2026,
                        new BigDecimal("200")));
        assertEquals(409, conflict.httpStatus());
        assertEquals("IDEMPOTENCY_CONFLICT", conflict.code());

        LicenseResult license = service.issueLicense("lic-1", first.applicationId());
        LicenseResult licenseReplay = service.issueLicense("lic-1", first.applicationId());
        assertEquals(license.licenseId(), licenseReplay.licenseId());
        // 重放不重复占用限额
        assertEquals(0, new BigDecimal("100").compareTo(service.getQuota(2026, "PINE").usedVolume()));

        ForestryException licenseConflict = assertThrows(ForestryException.class,
                () -> service.issueLicense("lic-1", 999999L));
        assertEquals("IDEMPOTENCY_CONFLICT", licenseConflict.code());
        assertEquals(0, new BigDecimal("100").compareTo(service.getQuota(2026, "PINE").usedVolume()));
    }

    @Test
    @DisplayName("验收同键重放：同参返回首次结果，异参 409")
    void acceptanceIdempotentReplay() {
        givenCommercialPlot("P1", 30, 25, "PINE");
        givenQuota(2026, "PINE", "1000");
        long task = confirmAndGetTask("app-1", "lic-1", "P1");

        AcceptanceResult first = service.submitAcceptance(task, "acc-1", new BigDecimal("90"));
        AcceptanceResult replay = service.submitAcceptance(task, "acc-1", new BigDecimal("90"));
        assertEquals(first.round(), replay.round());
        assertEquals("PASSED", replay.result());
        // 任务仍只有一轮验收
        assertEquals(1, service.getTask(task).round());

        ForestryException conflict = assertThrows(ForestryException.class,
                () -> service.submitAcceptance(task, "acc-1", new BigDecimal("80")));
        assertEquals("IDEMPOTENCY_CONFLICT", conflict.code());
    }

    // ---------- 小班唯一有效证 ----------

    @Test
    @DisplayName("同一小班同时刻只允许一张有效证")
    void oneActiveLicensePerPlot() {
        givenCommercialPlot("P1", 30, 25, "PINE");
        givenQuota(2026, "PINE", "1000");

        ApplicationResult a1 = service.submitApplication("app-1", "P1", "OP-1", 2026,
                new BigDecimal("100"));
        service.issueLicense("lic-1", a1.applicationId());

        ApplicationResult a2 = service.submitApplication("app-2", "P1", "OP-1", 2026,
                new BigDecimal("100"));
        assertEquals("APPROVED", a2.status());
        ForestryException ex = assertThrows(ForestryException.class,
                () -> service.issueLicense("lic-2", a2.applicationId()));
        assertEquals("PLOT_HAS_ACTIVE_LICENSE", ex.code());
    }

    // ---------- 归档终态 ----------

    @Test
    @DisplayName("归档为终态：重复归档幂等成功，迟到确认只记迟到事件")
    void archiveTerminalAndLateEvents() {
        givenCommercialPlot("P1", 30, 25, "PINE");
        givenQuota(2026, "PINE", "1000");
        ApplicationResult a1 = service.submitApplication("app-1", "P1", "OP-1", 2026,
                new BigDecimal("100"));
        LicenseResult license = service.issueLicense("lic-1", a1.applicationId());
        ConfirmResult confirmed = service.confirmHarvest(license.licenseId());

        ArchiveResult archived = service.archiveLicense(license.licenseId());
        assertEquals("ARCHIVED", archived.status());
        assertFalse(archived.idempotent());

        ArchiveResult again = service.archiveLicense(license.licenseId());
        assertEquals("ARCHIVED", again.status());
        assertTrue(again.idempotent());

        // 迟到确认：只记迟到事件，证件保持归档终态
        ConfirmResult lateConfirm = service.confirmHarvest(license.licenseId());
        assertTrue(lateConfirm.late());
        assertEquals("ARCHIVED", service.getLicense(license.licenseId()).status());

        // 迟到验收：同样只记迟到事件
        AcceptanceResult lateAcceptance = service.submitAcceptance(
                confirmed.regenerationTaskId(), "acc-late", new BigDecimal("90"));
        assertTrue(lateAcceptance.late());

        assertEquals(2, service.listLateEvents().size());
    }

    // ---------- 内部 ----------

    /** 申请、发证并确认，返回生成的迹地更新任务 ID。 */
    private long confirmAndGetTask(String appReq, String licReq, String plotCode) {
        ApplicationResult application = service.submitApplication(appReq, plotCode, "OP-1", 2026,
                new BigDecimal("100"));
        assertEquals("APPROVED", application.status());
        LicenseResult license = service.issueLicense(licReq, application.applicationId());
        ConfirmResult confirmed = service.confirmHarvest(license.licenseId());
        assertEquals("CONFIRMED", confirmed.status());
        return confirmed.regenerationTaskId();
    }
}
