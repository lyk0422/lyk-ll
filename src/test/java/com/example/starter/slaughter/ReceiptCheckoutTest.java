package com.example.starter.slaughter;

import com.example.starter.slaughter.domain.BatchStatus;
import com.example.starter.slaughter.domain.JudgmentCategory;
import com.example.starter.slaughter.dto.CheckoutResponse;
import com.example.starter.slaughter.dto.JudgmentItem;
import com.example.starter.slaughter.dto.ReceiptResponse;
import com.example.starter.slaughter.service.ServiceOutcome;
import com.example.starter.slaughter.web.BusinessException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 无害化处理回执勾稽：回执头只与同类判定头只恰等受理，不等拒绝出厂；
 * 出厂核销为终态，核销后迟到回执只记迟到事件并告警。
 */
class ReceiptCheckoutTest extends AbstractSlaughterTest {

    private final List<String> heads = List.of("H001", "H002", "H003", "H004");

    private void prepareBatch(String prefix, String batchNo) {
        declareAndPass(prefix, batchNo, heads);
        inspectAllAndComplete(prefix, batchNo, heads);
    }

    private List<JudgmentItem> twoRenderTwoDestroy() {
        return List.of(
                judge("H001", JudgmentCategory.QUALIFIED),
                judge("H002", JudgmentCategory.QUALIFIED),
                judge("H003", JudgmentCategory.RENDERED),
                judge("H004", JudgmentCategory.RENDERED));
    }

    @Test
    void receiptExactlyEqual_acceptedAndCheckoutSucceeds() {
        prepareBatch("ok", "B-RC-1");
        service.judge("B-RC-1", judgment("ok-judge", twoRenderTwoDestroy()));

        ServiceOutcome<ReceiptResponse> receipt = service.registerReceipt("B-RC-1",
                receipt("ok-receipt", "RCP-1", List.of("H003", "H004")));
        assertFalse(receipt.replayed());
        assertTrue(receipt.data().accepted());
        assertFalse(receipt.data().late());
        assertEquals(JudgmentCategory.RENDERED.name(), receipt.data().category());
        assertEquals(2, receipt.data().headCount());

        // 头只台账回执闭环、判定明细与回执勾稽均可复算。
        assertEquals(2L, jdbc.queryForObject(
                "SELECT COUNT(*) FROM batch_head WHERE batch_no = 'B-RC-1' AND receipt_id IS NOT NULL",
                Long.class));
        assertEquals(2L, jdbc.queryForObject(
                "SELECT COUNT(*) FROM receipt_head rh JOIN disposal_receipt dr ON rh.receipt_id = dr.id "
                        + "WHERE rh.batch_no = 'B-RC-1' AND dr.accepted = TRUE", Long.class));

        ServiceOutcome<CheckoutResponse> checkout = service.checkout(
                "B-RC-1", checkout("ok-checkout"));
        assertFalse(checkout.replayed());
        assertEquals(BatchStatus.CHECKED_OUT.name(), checkout.data().status());
        assertNotNull(checkout.data().checkedOutAtUtc());
    }

    @Test
    void receiptMismatch_rejectedAndBlocksCheckout() {
        prepareBatch("bad", "B-RC-2");
        service.judge("B-RC-2", judgment("bad-judge", twoRenderTwoDestroy()));

        // 回执只覆盖 1 头，与化制判定 2 头不勾稽：受理标记 false。
        ServiceOutcome<ReceiptResponse> receipt = service.registerReceipt("B-RC-2",
                receipt("bad-receipt", "RCP-2", List.of("H003")));
        assertFalse(receipt.data().accepted());
        assertEquals(1, jdbc.queryForObject(
                "SELECT head_count FROM disposal_receipt WHERE receipt_no = 'RCP-2'", Integer.class));
        // 不勾稽不得回写头只闭环。
        assertEquals(0L, jdbc.queryForObject(
                "SELECT COUNT(*) FROM batch_head WHERE batch_no = 'B-RC-2' AND receipt_id IS NOT NULL",
                Long.class));

        // 拒绝出厂。
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.checkout("B-RC-2", checkout("bad-checkout")));
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, ex.getStatus());
        assertEquals("DISPOSAL_NOT_CLOSED", ex.getCode());
        assertEquals(BatchStatus.JUDGED.name(), jdbc.queryForObject(
                "SELECT status FROM slaughter_batch WHERE batch_no = 'B-RC-2'", String.class));

        // 补齐恰等回执后闭环、可核销。
        ServiceOutcome<ReceiptResponse> fixed = service.registerReceipt("B-RC-2",
                receipt("bad-receipt-fix", "RCP-2B", List.of("H003", "H004")));
        assertTrue(fixed.data().accepted());
        service.checkout("B-RC-2", checkout("bad-checkout-2"));
        assertEquals(BatchStatus.CHECKED_OUT.name(), jdbc.queryForObject(
                "SELECT status FROM slaughter_batch WHERE batch_no = 'B-RC-2'", String.class));
    }

    @Test
    void receiptWithQualifiedHeadRejected() {
        prepareBatch("mix", "B-RC-3");
        service.judge("B-RC-3", judgment("mix-judge", twoRenderTwoDestroy()));
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.registerReceipt("B-RC-3",
                        receipt("mix-receipt", "RCP-3", List.of("H001", "H003"))));
        assertEquals("RECEIPT_HEAD_NOT_DISPOSAL", ex.getCode());
    }

    @Test
    void receiptMixedCategoriesRejected() {
        prepareBatch("cat", "B-RC-4");
        service.judge("B-RC-4", judgment("cat-judge", List.of(
                judge("H001", JudgmentCategory.QUALIFIED),
                judge("H002", JudgmentCategory.QUALIFIED),
                judge("H003", JudgmentCategory.RENDERED),
                judge("H004", JudgmentCategory.DESTROYED))));
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.registerReceipt("B-RC-4",
                        receipt("cat-receipt", "RCP-4", List.of("H003", "H004"))));
        assertEquals("RECEIPT_CATEGORY_MIXED", ex.getCode());
    }

    @Test
    void lateRenderedReceiptAfterCheckout_recordedAndBatchStaysTerminal() {
        String batchNo = "B-RC-6";
        prepareBatch("late2", batchNo);
        service.judge(batchNo, judgment("late2-judge", List.of(
                judge("H001", JudgmentCategory.QUALIFIED),
                judge("H002", JudgmentCategory.QUALIFIED),
                judge("H003", JudgmentCategory.QUALIFIED),
                judge("H004", JudgmentCategory.RENDERED))));
        service.registerReceipt(batchNo, receipt("late2-receipt-1", "RCP-6A", List.of("H004")));
        service.checkout(batchNo, checkout("late2-checkout"));

        // 终态后同头只再来一张回执：记迟到事件并告警，不改变批次状态。
        ServiceOutcome<ReceiptResponse> late = service.registerReceipt(batchNo,
                receipt("late2-receipt-2", "RCP-6B", List.of("H004")));
        assertTrue(late.data().late());
        assertTrue(late.data().alerted());
        assertFalse(late.data().accepted());
        assertEquals(BatchStatus.CHECKED_OUT.name(), jdbc.queryForObject(
                "SELECT status FROM slaughter_batch WHERE batch_no = ?", String.class, batchNo));
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM late_receipt_event WHERE batch_no = ?", Integer.class, batchNo));
        // 迟到回执头只不回写覆盖首次闭环。
        Long firstReceiptId = jdbc.queryForObject(
                "SELECT id FROM disposal_receipt WHERE receipt_no = 'RCP-6A'", Long.class);
        assertEquals(firstReceiptId, jdbc.queryForObject(
                "SELECT receipt_id FROM batch_head WHERE batch_no = ? AND head_no = 'H004'",
                Long.class, batchNo));
    }

    @Test
    void repeatedCheckout_isIdempotentSuccess() {
        prepareBatch("rep", "B-RC-7");
        service.judge("B-RC-7", judgment("rep-judge",
                heads.stream().map(h -> judge(h, JudgmentCategory.QUALIFIED)).toList()));
        var first = service.checkout("B-RC-7", checkout("rep-checkout-1"));
        var second = service.checkout("B-RC-7", checkout("rep-checkout-2"));

        assertTrue(second.replayed());
        assertEquals(BatchStatus.CHECKED_OUT.name(), second.data().status());
        assertEquals(first.data().checkedOutAtUtc(), second.data().checkedOutAtUtc());
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM slaughter_batch WHERE batch_no = 'B-RC-7'", Integer.class));
    }

    @Test
    void checkoutBeforeJudgmentRejected() {
        prepareBatch("early", "B-RC-8");
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.checkout("B-RC-8", checkout("early-checkout")));
        assertEquals(HttpStatus.CONFLICT, ex.getStatus());
        assertEquals("INVALID_BATCH_STATE", ex.getCode());
    }
}
