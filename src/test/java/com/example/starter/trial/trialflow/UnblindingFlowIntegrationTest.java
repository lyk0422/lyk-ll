package com.example.starter.trial.trialflow;

import com.example.starter.trial.api.AuditEventResponse;
import com.example.starter.trial.api.EnrollmentRequest;
import com.example.starter.trial.api.EnrollmentResponse;
import com.example.starter.trial.api.UnblindingConfirmRequest;
import com.example.starter.trial.api.UnblindingResponse;
import com.example.starter.trial.api.VisitRequest;
import com.example.starter.trial.api.VisitResponse;
import com.example.starter.trial.service.BusinessRuleException;
import com.example.starter.trial.service.ConflictException;
import com.example.starter.trial.service.ForbiddenException;
import com.example.starter.trial.service.TrialService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 应急揭盲双人确认、终态幂等、UTC 日界访视排除与审计顺序的 H2 事务测试。
 */
class UnblindingFlowIntegrationTest extends AbstractTrialIntegrationTest {

    private static final String TRIAL = "CT-2026-001";
    private static final String SITE = "SITE-01";
    private static final String SCREEN = "S-900";
    private static final String INV_A = "INV-001";
    private static final String INV_B = "INV-002";

    @Autowired
    private TrialService trialService;

    private EnrollmentResponse enrollSubject() {
        return trialService.enroll(new EnrollmentRequest(
                "REQ-ENR-" + SCREEN, TRIAL, SCREEN, SITE, Instant.parse("2026-03-01T05:30:00Z")));
    }

    private UnblindingConfirmRequest confirmation(String requestId, String confirmer) {
        return new UnblindingConfirmRequest(requestId, TRIAL, SCREEN, confirmer);
    }

    @Test
    void singleConfirmationStaysPendingAndDoesNotUnblind() {
        EnrollmentResponse enrolled = enrollSubject();

        UnblindingResponse first = trialService.confirmUnblinding(
                confirmation("UB-1", INV_A));
        assertEquals("PENDING", first.status());
        assertEquals(INV_A, first.confirmerA());
        assertNull(first.confirmerB());
        assertNull(first.unblindedGroupCode(), "单人确认不得暴露真实组别");
        assertFalse(first.replayed());

        // 受试者仍为盲态在组
        assertEquals("ENROLLED", trialService.getBlindedSubject(TRIAL, SCREEN).status());
        // 只产生一条审计事件
        List<AuditEventResponse> events = trialService.listAuditEvents(TRIAL, SCREEN);
        assertEquals(1, events.size());
        assertEquals("FIRST_CONFIRMED", events.get(0).eventType());
        assertEquals(enrolled.randomizationNo(), first.randomizationNo());
    }

    @Test
    void sameConfirmerCannotActAsSecondConfirmer() {
        enrollSubject();
        trialService.confirmUnblinding(confirmation("UB-2", INV_A));
        BusinessRuleException ex = assertThrows(BusinessRuleException.class,
                () -> trialService.confirmUnblinding(confirmation("UB-2", INV_A)));
        assertTrue(ex.getMessage().contains("different authorized user"));
        // 仍未生效
        assertEquals("PENDING", trialService.getUnblindingStatus(TRIAL, SCREEN).status());
    }

    @Test
    void unauthorizedUserCannotConfirm() {
        enrollSubject();
        assertThrows(ForbiddenException.class,
                () -> trialService.confirmUnblinding(confirmation("UB-3", "HACKER-9")));
        // 未授权确认不产生任何揭盲申请，受试者保持盲态
        assertEquals("ENROLLED", trialService.getBlindedSubject(TRIAL, SCREEN).status());
        assertThrows(com.example.starter.trial.service.NotFoundException.class,
                () -> trialService.getUnblindingStatus(TRIAL, SCREEN));
        assertTrue(trialService.listAuditEvents(TRIAL, SCREEN).isEmpty(),
                "被拒绝的确认不得写入审计事件");
    }

    @Test
    void twoDifferentConfirmersCompleteUnblindingAndRevealGroupInOneTx() {
        EnrollmentResponse enrolled = enrollSubject();
        clock.set(Instant.parse("2026-03-12T10:15:30Z"));

        trialService.confirmUnblinding(confirmation("UB-4", INV_A));
        UnblindingResponse completed = trialService.confirmUnblinding(confirmation("UB-4", INV_B));

        assertEquals("COMPLETED", completed.status());
        assertEquals(INV_A, completed.confirmerA());
        assertEquals(INV_B, completed.confirmerB());
        assertTrue(completed.unblindedGroupCode().equals("TREATMENT")
                || completed.unblindedGroupCode().equals("CONTROL"));
        // 揭盲后真实组别应与盲态编码自洽
        String expected = "A".equals(enrolled.blindedGroupCode()) ? "TREATMENT" : "CONTROL";
        assertEquals(expected, completed.unblindedGroupCode());

        // 受试者标记已揭盲（终态）
        assertEquals("UNBLINDED", trialService.getBlindedSubject(TRIAL, SCREEN).status());

        // 审计事件按顺序：FIRST_CONFIRMED → SECOND_CONFIRMED → UNBLINDED
        List<AuditEventResponse> events = trialService.listAuditEvents(TRIAL, SCREEN);
        assertEquals(3, events.size());
        assertEquals("FIRST_CONFIRMED", events.get(0).eventType());
        assertEquals("SECOND_CONFIRMED", events.get(1).eventType());
        assertEquals(INV_B, events.get(1).actorUserId());
        assertEquals("UNBLINDED", events.get(2).eventType());
        assertTrue(events.get(0).seq() < events.get(1).seq());
        assertTrue(events.get(1).seq() < events.get(2).seq());
    }

    @Test
    void completedUnblindingIsTerminalAndReplaysAddNoEvents() {
        enrollSubject();
        trialService.confirmUnblinding(confirmation("UB-5", INV_A));
        UnblindingResponse completed = trialService.confirmUnblinding(confirmation("UB-5", INV_B));

        // 用同一 requestId 重复确认（甚至换不同人）→ 返回首次结果，不新增事件
        UnblindingResponse replay1 = trialService.confirmUnblinding(confirmation("UB-5", "INV-003"));
        assertTrue(replay1.replayed());
        assertEquals(completed.confirmerA(), replay1.confirmerA());
        assertEquals(completed.confirmerB(), replay1.confirmerB());
        assertEquals(completed.unblindedGroupCode(), replay1.unblindedGroupCode());

        // 用新 requestId 再发起揭盲 → 仍返回首次终态结果
        UnblindingResponse replay2 = trialService.confirmUnblinding(confirmation("UB-5-NEW", INV_A));
        assertTrue(replay2.replayed());
        assertEquals("UB-5", replay2.requestId(), "必须返回首次申请编号");

        // 审计事件数保持为 3，终态不可回退
        assertEquals(3, trialService.listAuditEvents(TRIAL, SCREEN).size());
        assertEquals("UNBLINDED", trialService.getBlindedSubject(TRIAL, SCREEN).status());
    }

    @Test
    void secondUnblindingRequestWhilePendingConflicts() {
        enrollSubject();
        trialService.confirmUnblinding(confirmation("UB-6", INV_A));
        ConflictException ex = assertThrows(ConflictException.class,
                () -> trialService.confirmUnblinding(confirmation("UB-6-OTHER", INV_B)));
        assertTrue(ex.getMessage().contains("already pending"));
    }

    @Test
    void visitsOnAndAfterUnblindingUtcDayAreExcludedFromMainAnalysis() {
        enrollSubject();

        // 揭盲前登记两条访视（均纳入分析集）
        VisitResponse before1 = trialService.registerVisit(TRIAL, SCREEN,
                new VisitRequest(Instant.parse("2026-03-11T23:30:00Z"), "WEEK_1", 12.5));
        VisitResponse before2 = trialService.registerVisit(TRIAL, SCREEN,
                new VisitRequest(Instant.parse("2026-03-12T00:00:00Z"), "WEEK_2_EARLY", 13.0));
        assertFalse(before1.excludedFromMainAnalysis());
        assertFalse(before2.excludedFromMainAnalysis());

        // 2026-03-12 UTC 当日揭盲（时刻 10:15:30Z）
        clock.set(Instant.parse("2026-03-12T10:15:30Z"));
        trialService.confirmUnblinding(confirmation("UB-7", INV_A));
        trialService.confirmUnblinding(confirmation("UB-7", INV_B));

        // 揭盲事务把 UTC 日界（3-12 00:00Z）及之后的历史访视批量标记排除
        List<VisitResponse> visitsAfterUnblinding = trialService.listVisits(TRIAL, SCREEN);
        assertEquals(2, visitsAfterUnblinding.size());
        assertFalse(visitsAfterUnblinding.get(0).excludedFromMainAnalysis(),
                "3-11 的访视在揭盲日界之前，保留在主要分析集");
        assertTrue(visitsAfterUnblinding.get(1).excludedFromMainAnalysis(),
                "3-12 00:00Z 的访视在揭盲当日 UTC 日界及之后，必须排除");
        assertEquals("SUBJECT_UNBLINDED", visitsAfterUnblinding.get(1).exclusionReason());

        // 揭盲后新登记访视：当日的排除，前一日 23:59 的不排除
        VisitResponse laterVisit = trialService.registerVisit(TRIAL, SCREEN,
                new VisitRequest(Instant.parse("2026-03-13T09:00:00Z"), "WEEK_3", 14.0));
        assertTrue(laterVisit.excludedFromMainAnalysis());
        VisitResponse edgeBefore = trialService.registerVisit(TRIAL, SCREEN,
                new VisitRequest(Instant.parse("2026-03-11T23:59:59Z"), "RETRO_BEFORE", 9.0));
        assertFalse(edgeBefore.excludedFromMainAnalysis(), "UTC 前一日 23:59:59 不排除");
    }

    @Test
    void unblindedSubjectCannotBeReEnrolledOrModified() {
        enrollSubject();
        trialService.confirmUnblinding(confirmation("UB-8", INV_A));
        trialService.confirmUnblinding(confirmation("UB-8", INV_B));

        // 已揭盲受试者不得再参与分配相关修改：重复入组仍 409，且只有 1 名受试者
        assertThrows(ConflictException.class, () -> trialService.enroll(new EnrollmentRequest(
                "REQ-ENR-AGAIN", TRIAL, SCREEN, SITE, Instant.parse("2026-03-01T05:30:00Z"))));
        assertEquals(1L, jdbc.queryForObject(
                "SELECT COUNT(1) FROM trial_subject WHERE trial_id = ? AND subject_screen_no = ?",
                Long.class, TRIAL, SCREEN));
        assertEquals("UNBLINDED",
                jdbc.queryForObject("SELECT status FROM trial_subject WHERE trial_id = ? AND subject_screen_no = ?",
                        String.class, TRIAL, SCREEN));
    }
}
