package com.example.starter.epidemic;

import com.example.starter.epidemic.api.ActionResponse;
import com.example.starter.epidemic.api.AuditTrailResponse;
import com.example.starter.epidemic.api.CardResponse;
import com.example.starter.epidemic.api.CorrectCardRequest;
import com.example.starter.epidemic.api.ReviewRequest;
import com.example.starter.epidemic.api.SubmitCardRequest;
import com.example.starter.epidemic.domain.AuditAction;
import com.example.starter.epidemic.domain.CardStatus;
import com.example.starter.epidemic.service.BusinessRuleException;
import com.example.starter.epidemic.service.EpidemicCardService;
import com.example.starter.epidemic.service.IdempotencyConflictException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 订正版本链、幂等与病种变更重启审核（原初审结论作废留痕）。
 */
class CorrectionFlowTest extends AbstractIntegrationTest {

    @Autowired
    private EpidemicCardService service;

    @Test
    void correction_appendsVersion_historyKept_defaultReturnsLatest() {
        CardResponse card = submitTimely("P-CORR", "B02", "req-corr-init");

        CorrectCardRequest correction = new CorrectCardRequest(
                "req-corr-1", card.cardNo(), null, null, "订正后姓名", 41, null,
                null, null, null, null, null, null, null, "联系电话更正");
        CardResponse updated = service.correct(correction);

        assertEquals(2, updated.versionNo());
        assertEquals("订正后姓名", updated.patientName());
        assertEquals(CardStatus.PENDING_REVIEW, updated.status());

        // 默认查询返回最新版；历史版本仍完整保留。
        CardResponse queried = service.getCard(card.cardNo());
        assertEquals(2, queried.versionNo());
        assertEquals("订正后姓名", queried.patientName());
        assertEquals(2, countVersions(card.cardNo()));
    }

    @Test
    void correction_idempotentReplay_andSameKeyDifferentParams409() {
        CardResponse card = submitTimely("P-CID", "B02", "req-cid-init");
        CorrectCardRequest correction = new CorrectCardRequest(
                "req-cid-1", card.cardNo(), null, null, "姓名A", null, null,
                null, null, null, null, null, null, null, "原因");

        CardResponse r1 = service.correct(correction);
        CardResponse r2 = service.correct(correction);
        assertEquals(2, r1.versionNo());
        assertEquals(2, r2.versionNo());
        assertEquals(2, countVersions(card.cardNo()), "同键同参重放不得产生新版本");

        CorrectCardRequest conflict = new CorrectCardRequest(
                "req-cid-1", card.cardNo(), null, null, "姓名B-冲突", null, null,
                null, null, null, null, null, null, null, "原因");
        assertThrows(IdempotencyConflictException.class, () -> service.correct(conflict));
        assertEquals(2, countVersions(card.cardNo()), "同键异参 409 不得产生新版本");
    }

    @Test
    void diseaseChange_afterReview_resetsToPendingReview_andVoidsPriorApproval() {
        // 疑似甲类 A01 初报 → 初审通过 → 订正为确诊（病种变更类）→ 重新待审核且原结论作废留痕。
        SubmitCardRequest submit = submitRequest("req-dc-init", "P-DC", "A01",
                BASE_TIME.minusSeconds(3600));
        SubmitCardRequest suspected = new SubmitCardRequest(
                submit.requestId(), submit.patientId(), submit.patientName(), submit.age(),
                submit.gender(), submit.diseaseCode(), "SUSPECTED", submit.onsetDate(),
                submit.diagnosisTime(), submit.institutionCode(), submit.reporter(),
                submit.address(), submit.phone(), null);
        CardResponse card = service.submit(suspected);
        assertEquals(CardStatus.PENDING_REVIEW, card.status());

        ActionResponse reviewed = service.review(card.cardNo(), new ReviewRequest("auditor-01", "同意"));
        assertEquals(CardStatus.PENDING_FINAL, reviewed.status());

        CorrectCardRequest confirm = new CorrectCardRequest(
                "req-dc-1", card.cardNo(), null, "CONFIRMED", null, null, null,
                null, null, null, null, null, null, null, "疑似转确诊");
        CardResponse afterCorrection = service.correct(confirm);

        assertEquals(2, afterCorrection.versionNo());
        assertEquals("CONFIRMED", afterCorrection.caseType());
        assertEquals(CardStatus.PENDING_REVIEW, afterCorrection.status(),
                "病种变更类订正须重新起算审核流程");

        List<AuditTrailResponse> trails = service.getTrails(card.cardNo());
        assertTrue(trails.stream().anyMatch(t -> t.action() == AuditAction.REVIEW_APPROVED
                && t.versionNo() == 1), "原初审通过轨迹保留");
        AuditTrailResponse voided = trails.stream()
                .filter(t -> t.action() == AuditAction.REVIEW_VOIDED).findFirst()
                .orElseThrow(() -> new AssertionError("必须有原审核结论作废留痕"));
        assertEquals(1, voided.versionNo());
        assertTrue(voided.detail().contains("auditor-01"));

        // 重新初审通过后方可终审归档。
        service.review(card.cardNo(), new ReviewRequest("auditor-02", "复核确诊"));
        assertEquals(CardStatus.PENDING_FINAL, service.getCard(card.cardNo()).status());
    }

    @Test
    void diseaseCodeChange_restartsReview_andDedupFollowsNewDisease() {
        CardResponse card = submitTimely("P-DCODE", "B02", "req-dcode-init");
        service.review(card.cardNo(), new ReviewRequest("auditor-01", null));
        CorrectCardRequest change = new CorrectCardRequest(
                "req-dcode-1", card.cardNo(), "B01", null, null, null, null,
                null, null, null, null, null, null, null, "病种修正 B02->B01");
        CardResponse updated = service.correct(change);
        assertEquals("B01", updated.diseaseCode());
        assertEquals(CardStatus.PENDING_REVIEW, updated.status());
    }

    @Test
    void nonDiseaseCorrection_afterReview_keepsPendingFinal() {
        CardResponse card = submitTimely("P-KEEP", "B02", "req-keep-init");
        service.review(card.cardNo(), new ReviewRequest("auditor-01", null));
        CorrectCardRequest minor = new CorrectCardRequest(
                "req-keep-1", card.cardNo(), null, null, "仅改姓名", null, null,
                null, null, null, null, null, null, null, "文字订正");
        CardResponse updated = service.correct(minor);
        assertEquals(2, updated.versionNo());
        assertEquals(CardStatus.PENDING_FINAL, updated.status(), "非病种变更订正不应重启审核");
        assertFalse(service.getTrails(card.cardNo()).stream()
                .anyMatch(t -> t.action() == AuditAction.REVIEW_VOIDED));
    }

    private CardResponse submitTimely(String patientId, String diseaseCode, String requestId) {
        return service.submit(submitRequest(requestId, patientId, diseaseCode,
                BASE_TIME.minusSeconds(3600)));
    }

    private int countVersions(String cardNo) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM card_version WHERE card_no = ?",
                Integer.class, cardNo);
    }
}
