package com.example.starter.epidemic;

import com.example.starter.epidemic.api.ActionResponse;
import com.example.starter.epidemic.api.ArchiveRequest;
import com.example.starter.epidemic.api.CardResponse;
import com.example.starter.epidemic.api.CorrectCardRequest;
import com.example.starter.epidemic.api.ReviewRequest;
import com.example.starter.epidemic.api.SubmitCardRequest;
import com.example.starter.epidemic.api.WithdrawRequest;
import com.example.starter.epidemic.domain.CardStatus;
import com.example.starter.epidemic.service.BusinessRuleException;
import com.example.starter.epidemic.service.EpidemicCardService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 撤回终态、归档只读与重复归档幂等、非法状态操作。
 */
class StatusFlowTest extends AbstractIntegrationTest {

    @Autowired
    private EpidemicCardService service;

    @Test
    void withdraw_beforeReview_isTerminal_andCannotRecoverOrCorrect() {
        CardResponse card = submitTimely("P-WD", "req-wd-init");
        ActionResponse withdrawn = service.withdraw(card.cardNo(),
                new WithdrawRequest("reporter-1", "填报错误撤回"));
        assertEquals(CardStatus.WITHDRAWN, withdrawn.status());

        assertThrows(BusinessRuleException.class, () -> service.review(card.cardNo(),
                new ReviewRequest("auditor-01", null)), "撤回后初审必须拒绝");
        assertThrows(BusinessRuleException.class, () -> service.correct(
                new CorrectCardRequest("req-wd-1", card.cardNo(), null, null, "x", null,
                        null, null, null, null, null, null, null, null, "试图订正")));
        assertThrows(BusinessRuleException.class, () -> service.withdraw(card.cardNo(),
                new WithdrawRequest("reporter-1", null)), "撤回为终态不可重复撤回");
    }

    @Test
    void withdrawnCard_releasesDedupKey_allowingNewValidCard() {
        CardResponse card = submitTimely("P-WD2", "req-wd2-init");
        service.withdraw(card.cardNo(), new WithdrawRequest("reporter-1", null));
        // 撤回后同患者＋病种＋发病日期可重新报告。
        CardResponse again = service.submit(submitRequest("req-wd2-new", "P-WD2", "B02",
                BASE_TIME.minusSeconds(3600)));
        assertEquals(CardStatus.PENDING_REVIEW, again.status());
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM card", Integer.class));
    }

    @Test
    void reviewThenArchive_cardReadOnly_correctionRejected_archiveIdempotent() {
        CardResponse card = submitTimely("P-AR", "req-ar-init");
        service.review(card.cardNo(), new ReviewRequest("auditor-01", "同意"));
        ActionResponse archived = service.archive(card.cardNo(),
                new ArchiveRequest("final-01", "终审归档"));
        assertEquals(CardStatus.ARCHIVED, archived.status());

        BusinessRuleException rejected = assertThrows(BusinessRuleException.class,
                () -> service.correct(new CorrectCardRequest("req-ar-1", card.cardNo(),
                        null, null, "归档后改名", null, null, null, null, null, null,
                        null, null, null, "试图订正")));
        assertTrue(rejected.getMessage().contains("特殊订正"));

        // 重复归档幂等成功，不新增轨迹终态动作以外的异常。
        ActionResponse again = service.archive(card.cardNo(), new ArchiveRequest("final-02", null));
        assertEquals(CardStatus.ARCHIVED, again.status());
        assertTrue(again.message().contains("幂等"));
    }

    @Test
    void cannotReview_orArchive_inWrongStatus() {
        CardResponse card = submitTimely("P-WS", "req-ws-init");
        assertThrows(BusinessRuleException.class, () -> service.archive(card.cardNo(),
                new ArchiveRequest("final-01", null)), "未初审不可归档");
        service.review(card.cardNo(), new ReviewRequest("auditor-01", null));
        assertThrows(BusinessRuleException.class, () -> service.review(card.cardNo(),
                new ReviewRequest("auditor-02", null)), "待终审状态不可重复初审");
    }

    private CardResponse submitTimely(String patientId, String requestId) {
        SubmitCardRequest req = submitRequest(requestId, patientId, "B02",
                BASE_TIME.minusSeconds(3600));
        return service.submit(req);
    }
}
