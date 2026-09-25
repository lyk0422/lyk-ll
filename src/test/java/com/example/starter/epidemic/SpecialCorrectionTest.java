package com.example.starter.epidemic;

import com.example.starter.epidemic.api.ArchiveRequest;
import com.example.starter.epidemic.api.CardResponse;
import com.example.starter.epidemic.api.ReviewRequest;
import com.example.starter.epidemic.api.SpecialConfirmRequest;
import com.example.starter.epidemic.api.SpecialCorrectRequest;
import com.example.starter.epidemic.api.SpecialCorrectionResponse;
import com.example.starter.epidemic.domain.AuditAction;
import com.example.starter.epidemic.domain.CardStatus;
import com.example.starter.epidemic.domain.SpecialStatus;
import com.example.starter.epidemic.service.BusinessRuleException;
import com.example.starter.epidemic.service.EpidemicCardService;
import com.example.starter.epidemic.service.IdempotencyConflictException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 归档后特殊订正：仅归档卡可发起；两名不同审核员先后确认才生效，单人确认不改变卡片数据。
 */
class SpecialCorrectionTest extends AbstractIntegrationTest {

    @Autowired
    private EpidemicCardService service;

    @Test
    void specialCorrection_requiresTwoDifferentReviewers_singleConfirmDoesNotChangeCard() {
        CardResponse card = archivedCard("P-SP1", "req-sp1-init");

        SpecialCorrectionResponse started = service.startSpecial(new SpecialCorrectRequest(
                "req-sp1-special", card.cardNo(), "B01", null, null,
                "疫情复核：实际诊断应为病毒性肝炎", "cdc-user-1"));
        assertEquals(SpecialStatus.WAITING_SECOND, started.status());
        assertNull(started.firstReviewer());
        assertNull(started.effectiveVersion());

        // 第一名审核员确认：卡片数据与版本均不变。
        SpecialCorrectionResponse first = service.confirmSpecial(
                new SpecialConfirmRequest(started.specialId(), "auditor-01"));
        assertEquals("auditor-01", first.firstReviewer());
        assertEquals(SpecialStatus.WAITING_SECOND, first.status());
        assertNull(first.effectiveVersion());

        CardResponse stillArchived = service.getCard(card.cardNo());
        assertEquals(1, stillArchived.versionNo(), "单人确认不得产生新版本");
        assertEquals("B02", stillArchived.diseaseCode(), "单人确认不得改变卡片数据");
        assertEquals(CardStatus.ARCHIVED, stillArchived.status());

        // 同一人再次确认：拒绝。
        assertThrows(BusinessRuleException.class, () -> service.confirmSpecial(
                new SpecialConfirmRequest(started.specialId(), "auditor-01")));

        // 另一名不同审核员确认后生效。
        SpecialCorrectionResponse second = service.confirmSpecial(
                new SpecialConfirmRequest(started.specialId(), "auditor-02"));
        assertEquals(SpecialStatus.EFFECTIVE, second.status());
        assertEquals(2, second.effectiveVersion());

        CardResponse after = service.getCard(card.cardNo());
        assertEquals(2, after.versionNo());
        assertEquals("B01", after.diseaseCode());
        assertEquals(CardStatus.ARCHIVED, after.status(), "生效后仍为归档只读终态");

        assertTrue(service.getTrails(card.cardNo()).stream()
                .anyMatch(t -> t.action() == AuditAction.SPECIAL_FIRST_CONFIRMED
                        && "auditor-01".equals(t.operator())));
        assertTrue(service.getTrails(card.cardNo()).stream()
                .anyMatch(t -> t.action() == AuditAction.SPECIAL_SECOND_CONFIRMED
                        && "auditor-02".equals(t.operator())));

        // 已生效流程重复确认幂等返回，不再产生版本。
        SpecialCorrectionResponse replay = service.confirmSpecial(
                new SpecialConfirmRequest(started.specialId(), "auditor-03"));
        assertEquals(SpecialStatus.EFFECTIVE, replay.status());
        assertEquals(2, service.getCard(card.cardNo()).versionNo());
    }

    @Test
    void specialCorrection_onlyAllowedOnArchivedCard_andIsIdempotent() {
        CardResponse card = service.submit(submitRequest("req-sp2-init", "P-SP2", "B02",
                BASE_TIME.minusSeconds(3600)));
        assertThrows(BusinessRuleException.class, () -> service.startSpecial(
                new SpecialCorrectRequest("req-sp2-special", card.cardNo(), "B01", null, null,
                        "未归档即申请", "cdc-user-1")));

        service.review(card.cardNo(), new ReviewRequest("auditor-01", null));
        service.archive(card.cardNo(), new ArchiveRequest("final-01", null));

        SpecialCorrectRequest req = new SpecialCorrectRequest("req-sp2-special", card.cardNo(),
                "B01", null, null, "复核结论", "cdc-user-1");
        Long id1 = service.startSpecial(req).specialId();
        Long id2 = service.startSpecial(req).specialId();
        assertEquals(id1, id2, "同键同参重放返回同一流程");

        SpecialCorrectRequest changed = new SpecialCorrectRequest("req-sp2-special", card.cardNo(),
                "B03", null, null, "参数被篡改", "cdc-user-1");
        assertThrows(IdempotencyConflictException.class, () -> service.startSpecial(changed));
    }

    private CardResponse archivedCard(String patientId, String requestId) {
        CardResponse card = service.submit(submitRequest(requestId, patientId, "B02",
                BASE_TIME.minusSeconds(3600)));
        service.review(card.cardNo(), new ReviewRequest("auditor-01", "同意"));
        service.archive(card.cardNo(), new ArchiveRequest("final-01", "终审"));
        return service.getCard(card.cardNo());
    }
}
