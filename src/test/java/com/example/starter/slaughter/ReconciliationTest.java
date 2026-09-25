package com.example.starter.slaughter;

import com.example.starter.slaughter.domain.JudgmentCategory;
import com.example.starter.slaughter.dto.ReconciliationResponse;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 头只台账、判定明细与回执勾稽实时复算验证。
 */
class ReconciliationTest extends AbstractSlaughterTest {

    private final List<String> heads = List.of("H001", "H002", "H003", "H004");

    @Test
    void fullyClosedFlow_reconcilesConsistent() {
        String batchNo = "B-REC-1";
        declareAndPass("rec", batchNo, heads);
        inspectAllAndComplete("rec", batchNo, heads);
        service.judge(batchNo, judgment("rec-judge", List.of(
                judge("H001", JudgmentCategory.QUALIFIED),
                conditional("H002", "本区"),
                judge("H003", JudgmentCategory.RENDERED),
                judge("H004", JudgmentCategory.DESTROYED))));
        service.registerReceipt(batchNo, receipt("rec-r-r", "REC-R", List.of("H003")));
        service.registerReceipt(batchNo, receipt("rec-d-r", "REC-D", List.of("H004")));

        ReconciliationResponse rec = service.reconcile(batchNo);
        assertEquals(4, rec.headCount());
        assertEquals(4, rec.inspectedCount());
        assertTrue(rec.missingHeads().isEmpty());
        assertEquals(1, rec.categoryCounts().get("QUALIFIED"));
        assertEquals(1, rec.categoryCounts().get("CONDITIONAL"));
        assertEquals(1, rec.categoryCounts().get("RENDERED"));
        assertEquals(1, rec.categoryCounts().get("DESTROYED"));
        assertEquals(2, rec.inventoryMarkCount());
        assertTrue(rec.unclosedDisposalHeads().isEmpty());
        assertEquals(2, rec.receipts().size());
        assertTrue(rec.receipts().stream().allMatch(ReconciliationResponse.ReceiptView::accepted));
        assertTrue(rec.consistent());
    }

    @Test
    void mismatchedReceipt_reconcilesInconsistentAndListsUnclosedHeads() {
        String batchNo = "B-REC-2";
        declareAndPass("rec2", batchNo, heads);
        inspectAllAndComplete("rec2", batchNo, heads);
        service.judge(batchNo, judgment("rec2-judge", List.of(
                judge("H001", JudgmentCategory.QUALIFIED),
                judge("H002", JudgmentCategory.QUALIFIED),
                judge("H003", JudgmentCategory.RENDERED),
                judge("H004", JudgmentCategory.RENDERED))));
        service.registerReceipt(batchNo, receipt("rec2-receipt", "REC-2", List.of("H003")));

        ReconciliationResponse rec = service.reconcile(batchNo);
        assertFalse(rec.consistent());
        assertFalse(rec.receipts().get(0).accepted());
        // 不勾稽不受理：头只回执均不回写，H003 与 H004 都处于未闭环。
        assertEquals(List.of("H003", "H004"), rec.unclosedDisposalHeads());
    }

    @Test
    void suspendedBatch_reconcilesWithMissingHeads() {
        String batchNo = "B-REC-3";
        declareAndPass("rec3", batchNo, heads);
        service.registerInspection(batchNo, new com.example.starter.slaughter.dto.InspectionRegisterRequest(
                "rec3-insp",
                List.of(new com.example.starter.slaughter.dto.InspectionItem(
                        "H001", com.example.starter.slaughter.domain.InspectionResult.NORMAL),
                        new com.example.starter.slaughter.dto.InspectionItem(
                                "H002", com.example.starter.slaughter.domain.InspectionResult.NORMAL),
                        new com.example.starter.slaughter.dto.InspectionItem(
                                "H003", com.example.starter.slaughter.domain.InspectionResult.NORMAL))));
        service.completeInspection(batchNo,
                new com.example.starter.slaughter.dto.InspectionCompleteRequest("rec3-complete"));

        ReconciliationResponse rec = service.reconcile(batchNo);
        assertEquals(3, rec.inspectedCount());
        assertEquals(List.of("H004"), rec.missingHeads());
        assertTrue(rec.consistent(), "漏检是合法暂停状态，计数本身仍可复算一致");
    }
}
