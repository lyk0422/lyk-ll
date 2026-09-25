package com.example.starter.survey.service;

import com.example.starter.survey.AbstractIntegrationTest;
import com.example.starter.survey.MutableClockConfig;
import com.example.starter.survey.api.Dtos;
import com.example.starter.survey.model.SurveyEnums.CertificateStatus;
import com.example.starter.survey.model.SurveyEnums.DefectStatus;
import com.example.starter.survey.model.SurveyEnums.Severity;
import com.example.starter.survey.model.SurveyEnums.SurveyStatus;
import com.example.starter.survey.web.ApiException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 船级检验主流程 H2 集成测试：窗口边界、展期、缺陷整改/复查、
 * 发证原子交接、幂等重放与签发终态。
 */
class SurveyFlowIntegrationTest extends AbstractIntegrationTest {

    private static final String SHIP1 = "IMO9000001"; // 周年日 06-15
    private static final String SHIP2 = "IMO9000002"; // 周年日 11-30

    @Autowired
    private SurveyService service;

    // ---------- 年度检验窗口首末日恰达 ----------

    @Test
    void boardExactlyOnWindowFirstDayIsAccepted() {
        MutableClockConfig.setToday(LocalDate.of(2026, 3, 15));
        Long surveyId = apply(SHIP1, "ANNUAL", "r-apply-first");
        Dtos.SurveyView view = board(surveyId, LocalDate.of(2026, 3, 15), List.of(), "r-board-first");
        assertEquals(SurveyStatus.BOARDING.name(), view.status());
        assertEquals(LocalDate.of(2026, 3, 15), view.windowStart());
        assertEquals(LocalDate.of(2026, 9, 15), view.windowEnd());
        assertEquals(LocalDate.of(2026, 3, 15), view.boardedAt());
        assertEquals("IN_WINDOW", service.listLedger(SHIP1).get(0).state());
    }

    @Test
    void boardExactlyOnWindowLastDayIsAccepted() {
        Long surveyId = apply(SHIP1, "ANNUAL", "r-apply-last");
        Dtos.SurveyView view = board(surveyId, LocalDate.of(2026, 9, 15), List.of(), "r-board-last");
        assertEquals(SurveyStatus.BOARDING.name(), view.status());
        assertEquals(LocalDate.of(2026, 9, 15), view.boardedAt());
    }

    @Test
    void boardOneDayOutsideWindowIsRejected() {
        Long surveyId = apply(SHIP1, "ANNUAL", "r-apply-out");
        ApiException ex = assertThrows(ApiException.class, () -> board(surveyId,
                LocalDate.of(2026, 9, 16), List.of(), "r-board-out"));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatus());
        // 被拒后检验单仍停留在 APPLIED，窗口外完成不入台账 IN_WINDOW。
        assertEquals(SurveyStatus.APPLIED.name(), service.getSurvey(surveyId).status());
    }

    @Test
    void crossYearWindowFirstDayAccepted() {
        // 周年日 11-30：2026 周期窗口 2026-08-30 至 2027-02-28（2027 平年）。
        Long surveyId = apply(SHIP2, "ANNUAL", "r-apply-x");
        Dtos.SurveyView view = board(surveyId, LocalDate.of(2027, 2, 28), List.of(), "r-board-x");
        assertEquals(SurveyStatus.BOARDING.name(), view.status());
        Dtos.LedgerView ledger = service.listLedger(SHIP2).get(0);
        assertEquals(2026, ledger.cycleYear());
        assertEquals("IN_WINDOW", ledger.state());
    }

    // ---------- 重大缺陷拒发证 ----------

    @Test
    void majorDefectBlocksIssueUntilRectified() {
        Long surveyId = apply(SHIP1, "ANNUAL", "r-apply-major");
        board(surveyId, LocalDate.of(2026, 6, 15),
                List.of(new Dtos.DefectInput("MAJOR", "主机基座裂纹")), "r-board-major");

        ApiException blocked = assertThrows(ApiException.class,
                () -> issue(surveyId, LocalDate.of(2026, 6, 20), "r-issue-blocked"));
        assertEquals(HttpStatus.BAD_REQUEST, blocked.getStatus());
        assertTrue(blocked.getMessage().contains("重大缺陷"));
        assertEquals(SurveyStatus.RECTIFYING.name(), service.getSurvey(surveyId).status());

        Long defectId = service.listDefects(surveyId).get(0).id();
        rectify(defectId, LocalDate.of(2026, 6, 25), "r-rect-major");
        Dtos.DefectView after = service.listDefects(surveyId).get(0);
        assertEquals(DefectStatus.ACCEPTED.name(), after.status());

        Dtos.CertificateView cert = issue(surveyId, LocalDate.of(2026, 6, 26), "r-issue-major");
        assertEquals("VALID", cert.status());
        assertNull(cert.recheckDue(), "重大缺陷整改即受理，证书不应挂复查截止");
    }

    // ---------- 一般缺陷恰第 30 日复查 / 逾期证书失效 ----------

    @Test
    void generalDefectRecheckExactlyOnDay30Accepted() {
        Long surveyId = apply(SHIP1, "ANNUAL", "r-apply-d30");
        board(surveyId, LocalDate.of(2026, 6, 15),
                List.of(new Dtos.DefectInput("GENERAL", "救生艇标记不清")), "r-board-d30");
        Long defectId = service.listDefects(surveyId).get(0).id();
        rectify(defectId, LocalDate.of(2026, 6, 20), "r-rect-d30");

        // 附条件发证：证书挂复查截止 2026-07-15（发现日+30）。
        Dtos.CertificateView cert = issue(surveyId, LocalDate.of(2026, 6, 26), "r-issue-d30");
        assertEquals("VALID", cert.status());
        assertEquals(LocalDate.of(2026, 7, 15), cert.recheckDue());

        // 恰第 30 日提交受理，证书仍有效。
        Idempotency.Result result = service.recheckDefect(new Dtos.RecheckDefectRequest(
                "r-recheck-d30", defectId, LocalDate.of(2026, 7, 15)));
        Dtos.RecheckResult recheck = (Dtos.RecheckResult) result.body();
        assertEquals(DefectStatus.ACCEPTED.name(), recheck.defect().status());
        assertNull(recheck.expiredCertificate());
        assertEquals("VALID", service.getValidCertificate(SHIP1, "ANNUAL").status());
    }

    @Test
    void generalDefectRecheckOnDay31ExpiresCertificate() {
        Long surveyId = apply(SHIP1, "ANNUAL", "r-apply-d31");
        board(surveyId, LocalDate.of(2026, 6, 15),
                List.of(new Dtos.DefectInput("GENERAL", "消防管轻微渗漏")), "r-board-d31");
        Long defectId = service.listDefects(surveyId).get(0).id();
        rectify(defectId, LocalDate.of(2026, 6, 20), "r-rect-d31");
        Dtos.CertificateView cert = issue(surveyId, LocalDate.of(2026, 6, 26), "r-issue-d31");

        Idempotency.Result result = service.recheckDefect(new Dtos.RecheckDefectRequest(
                "r-recheck-d31", defectId, LocalDate.of(2026, 7, 16)));
        Dtos.RecheckResult recheck = (Dtos.RecheckResult) result.body();
        assertEquals(DefectStatus.EXPIRED.name(), recheck.defect().status());
        assertNotNull(recheck.expiredCertificate());
        assertEquals(CertificateStatus.EXPIRED.name(), recheck.expiredCertificate().status());
        assertEquals(cert.id(), recheck.expiredCertificate().id());
        assertNull(service.getValidCertificate(SHIP1, "ANNUAL"), "逾期后无有效证书");
    }

    // ---------- 展期：恰 3 个月一次、特检禁展期、原子更新 ----------

    @Test
    void annualExtensionExactlyThreeMonthsApprovedOnceAtomically() {
        Long surveyId = apply(SHIP1, "ANNUAL", "r-apply-ext");
        board(surveyId, LocalDate.of(2026, 6, 15), List.of(), "r-board-ext");
        Dtos.CertificateView cert = issue(surveyId, LocalDate.of(2026, 6, 16), "r-issue-ext");
        LocalDate oldUntil = cert.validUntil();
        assertEquals(LocalDate.of(2027, 9, 15), oldUntil);

        Idempotency.Result applyResult = service.applyExtension(
                new Dtos.ApplyExtensionRequest("r-ext-apply", SHIP1, "ANNUAL", 3));
        Dtos.ExtensionView ext = (Dtos.ExtensionView) applyResult.body();
        assertEquals("PENDING", ext.status());
        assertEquals(3, ext.months());

        Dtos.ExtensionView approved = approve(ext.id(), "r-ext-approve");
        assertEquals("APPROVED", approved.status());
        assertEquals(oldUntil, approved.oldValidUntil());
        assertEquals(LocalDate.of(2027, 12, 15), approved.newValidUntil());

        // 批准与证书更新在同一事务：读到的有效证书有效期已延长。
        Dtos.CertificateView reloaded = service.getValidCertificate(SHIP1, "ANNUAL");
        assertEquals(LocalDate.of(2027, 12, 15), reloaded.validUntil());
        assertEquals(cert.id(), reloaded.id(), "展期不生成新证书版本");

        // 年度检验只可展期一次。
        ApiException second = assertThrows(ApiException.class, () -> service.applyExtension(
                new Dtos.ApplyExtensionRequest("r-ext-apply-2", SHIP1, "ANNUAL", 1)));
        assertEquals(HttpStatus.CONFLICT, second.getStatus());
    }

    @Test
    void specialSurveyCannotBeExtended() {
        Long surveyId = apply(SHIP1, "SPECIAL", "r-apply-spec");
        board(surveyId, LocalDate.of(2026, 6, 15), List.of(), "r-board-spec");
        issue(surveyId, LocalDate.of(2026, 6, 16), "r-issue-spec");

        ApiException ex = assertThrows(ApiException.class, () -> service.applyExtension(
                new Dtos.ApplyExtensionRequest("r-ext-spec", SHIP1, "SPECIAL", 3)));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatus());
        assertTrue(ex.getMessage().contains("不得展期"));
    }

    @Test
    void intermediateSurveyCannotBeExtendedAndMonthsMustBeWithinThree() {
        Long surveyId = apply(SHIP1, "INTERMEDIATE", "r-apply-int");
        board(surveyId, LocalDate.of(2026, 6, 15), List.of(), "r-board-int");
        issue(surveyId, LocalDate.of(2026, 6, 16), "r-issue-int");

        ApiException ex = assertThrows(ApiException.class, () -> service.applyExtension(
                new Dtos.ApplyExtensionRequest("r-ext-int", SHIP1, "INTERMEDIATE", 3)));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatus());

        // 年度展期 4 个月非法，恰 3 合法已在上例验证。
        Long annualId = apply(SHIP1, "ANNUAL", "r-apply-4m");
        // 等上一张年度证书？同船年度无在办检验后新申请；中间检验与年度不同槽位。
        board(annualId, LocalDate.of(2026, 6, 17), List.of(), "r-board-4m");
        issue(annualId, LocalDate.of(2026, 6, 18), "r-issue-4m");
        ApiException tooLong = assertThrows(ApiException.class, () -> service.applyExtension(
                new Dtos.ApplyExtensionRequest("r-ext-4m", SHIP1, "ANNUAL", 4)));
        assertEquals(HttpStatus.BAD_REQUEST, tooLong.getStatus());
    }

    // ---------- 证书原子交接 ----------

    @Test
    void newCertificateAtomicallyReplacesOldOne() {
        Long firstSurvey = apply(SHIP1, "ANNUAL", "r-apply-c1");
        board(firstSurvey, LocalDate.of(2026, 6, 15), List.of(), "r-board-c1");
        Dtos.CertificateView first = issue(firstSurvey, LocalDate.of(2026, 6, 16), "r-issue-c1");
        assertEquals(1, first.version());

        // 新周期再做一次年度检验并发证。
        Long secondSurvey = apply(SHIP1, "ANNUAL", "r-apply-c2");
        board(secondSurvey, LocalDate.of(2027, 6, 15), List.of(), "r-board-c2");
        Dtos.CertificateView second = issue(secondSurvey, LocalDate.of(2027, 6, 16), "r-issue-c2");
        assertEquals(2, second.version());

        // 同一“船＋类型”仅一证有效；旧证同事务失效。
        Dtos.CertificateView valid = service.getValidCertificate(SHIP1, "ANNUAL");
        assertEquals(second.id(), valid.id());
        List<Dtos.CertificateView> chain = service.listCertificateChain(SHIP1, "ANNUAL");
        assertEquals(2, chain.size());
        assertEquals(CertificateStatus.INVALID.name(), chain.get(0).status());
        assertNotNull(chain.get(0).invalidatedAt(), "旧证失效日应等于新证签发日");
        assertEquals(second.issuedAt(), chain.get(0).invalidatedAt());
        assertEquals(CertificateStatus.VALID.name(), chain.get(1).status());
        assertEquals(1L, jdbc.queryForObject(
                "SELECT COUNT(*) FROM certificate WHERE ship_id = 1 AND type_code = 'ANNUAL' AND valid_flag = 1",
                Long.class));
    }

    // ---------- 同键重放 / 异参冲突 / 签发终态 ----------

    @Test
    void sameRequestIdSameParamsReplaysFirstResult() {
        Idempotency.Result first = service.applySurvey(
                new Dtos.ApplySurveyRequest("r-replay", SHIP1, "ANNUAL"));
        assertFalse(first.replay());
        Idempotency.Result second = service.applySurvey(
                new Dtos.ApplySurveyRequest("r-replay", SHIP1, "ANNUAL"));
        assertTrue(second.replay());
        assertEquals(first.status(), second.status());
        assertEquals(1L, count("survey"), "重放不得产生第二张检验单");
    }

    @Test
    void sameRequestIdDifferentParamsConflictsWithoutSideEffect() {
        service.applySurvey(new Dtos.ApplySurveyRequest("r-conflict", SHIP1, "ANNUAL"));
        ApiException ex = assertThrows(ApiException.class, () -> service.applySurvey(
                new Dtos.ApplySurveyRequest("r-conflict", SHIP1, "SPECIAL")));
        assertEquals(HttpStatus.CONFLICT, ex.getStatus());
        assertEquals(1L, count("survey"), "异参冲突不得产生新检验单");
        assertEquals(1L, count("idempotent_request"), "不得产生第二条幂等记录");
    }

    @Test
    void issueIsTerminalAndReplayIsIdempotent() {
        Long surveyId = apply(SHIP1, "ANNUAL", "r-apply-term");
        board(surveyId, LocalDate.of(2026, 6, 15), List.of(), "r-board-term");
        Dtos.CertificateView first = issue(surveyId, LocalDate.of(2026, 6, 16), "r-issue-term");

        // 同 requestId 重放：返回首次证书。
        Idempotency.Result replay = service.issueCertificate(
                new Dtos.IssueCertificateRequest("r-issue-term", surveyId,
                        LocalDate.of(2026, 6, 16)));
        assertTrue(replay.replay());

        // 新 requestId 对已发证检验单再次签发：幂等返回既有证书，不产生新版本。
        Dtos.CertificateView again = issue(surveyId, LocalDate.of(2026, 6, 17), "r-issue-again");
        assertEquals(first.id(), again.id());
        assertEquals(1, service.listCertificateChain(SHIP1, "ANNUAL").size());
        assertEquals(SurveyStatus.ISSUED.name(), service.getSurvey(surveyId).status());

        // 发证后迟到的登轮/记录不改变终态：迟到记录只追加事件。
        Idempotency.Result late = service.lateRecord(new Dtos.LateRecordRequest(
                "r-late", surveyId, LocalDate.of(2026, 7, 1), "船东补交温度记录"));
        Dtos.LateRecordResult lateResult = (Dtos.LateRecordResult) late.body();
        assertTrue(lateResult.terminal());
        assertEquals(SurveyStatus.ISSUED.name(), service.getSurvey(surveyId).status());
        long lateEvents = jdbc.queryForObject(
                "SELECT COUNT(*) FROM survey_event WHERE survey_id = ? AND event_type = 'LATE_RECORD'",
                Long.class, surveyId);
        assertEquals(1L, lateEvents);
    }

    @Test
    void recomputeReproducesLedgerAndChains() {
        Long surveyId = apply(SHIP1, "ANNUAL", "r-apply-re");
        board(surveyId, LocalDate.of(2026, 6, 15),
                List.of(new Dtos.DefectInput("GENERAL", "航行灯故障")), "r-board-re");
        Long defectId = service.listDefects(surveyId).get(0).id();
        rectify(defectId, LocalDate.of(2026, 6, 18), "r-rect-re");
        issue(surveyId, LocalDate.of(2026, 6, 19), "r-issue-re");
        service.recheckDefect(new Dtos.RecheckDefectRequest(
                "r-recheck-re", defectId, LocalDate.of(2026, 7, 10)));

        Idempotency.Result result = service.recompute(
                new Dtos.RecomputeRequest("r-recompute", LocalDate.of(2026, 8, 1)));
        Dtos.RecomputeResult summary = (Dtos.RecomputeResult) result.body();
        assertTrue(summary.ledgerCycles() >= 1);
        assertTrue(summary.certificateChains() >= 1);
        assertTrue(summary.defectChainDigest().contains("GENERAL:ACCEPTED"));
    }

    // ---------- helpers ----------

    private Long apply(String imo, String type, String requestId) {
        Idempotency.Result result = service.applySurvey(
                new Dtos.ApplySurveyRequest(requestId, imo, type));
        return ((Dtos.SurveyView) result.body()).id();
    }

    private Dtos.SurveyView board(Long surveyId, LocalDate date,
                                  List<Dtos.DefectInput> defects, String requestId) {
        Idempotency.Result result = service.boardSurvey(
                new Dtos.BoardSurveyRequest(requestId, surveyId, date, defects));
        return (Dtos.SurveyView) result.body();
    }

    private void rectify(Long defectId, LocalDate date, String requestId) {
        service.rectifyDefect(new Dtos.RectifyDefectRequest(requestId, defectId, date));
    }

    private Dtos.CertificateView issue(Long surveyId, LocalDate date, String requestId) {
        Idempotency.Result result = service.issueCertificate(
                new Dtos.IssueCertificateRequest(requestId, surveyId, date));
        return (Dtos.CertificateView) result.body();
    }

    private Dtos.ExtensionView approve(Long extensionId, String requestId) {
        Idempotency.Result result = service.approveExtension(
                new Dtos.ApproveExtensionRequest(requestId, extensionId));
        return (Dtos.ExtensionView) result.body();
    }
}
