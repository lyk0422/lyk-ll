package com.example.rebate.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.rebate.testsupport.TestFixtures;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

/**
 * 冲回主流程：结案批次提交→复核批准占用→分批追缴到账恰收齐→完成只读；
 * 校验版本号、余额台账、结案总额与批次状态不变。
 */
class ReversalMainFlowTest extends ReversalApiTest {

    /** FOB 1000 × 13% = 130.0000 元 = 13000 分。 */
    private static final long FROZEN_CENTS = 13000L;

    @Test
    void submitReviewApprovePartialReceiptsThenComplete() throws Exception {
        seedClosedBatch("B-CLOSE", "CD-CLOSE", "1000", "20");

        // 提交 5000 分，初为 PENDING、版本 1，不占额
        JsonNode submitted = postJson(reversals("B-CLOSE"),
                TestFixtures.reversalSubmit("rev-sub-1", "CD-CLOSE", "CORR-1",
                        "事后核查多退", 5000, "clerk-a"), 200);
        assertEquals("PENDING", submitted.get("status").asText());
        assertEquals(1, submitted.get("version").asInt());
        assertEquals(FROZEN_CENTS, submitted.get("frozenTaxCents").asLong());
        assertEquals(0, submitted.get("occupiedCents").asLong());
        assertEquals(FROZEN_CENTS, submitted.get("remainingCents").asLong());

        // 余额查询：冻结 13000、占用 0、待复核 5000
        JsonNode balancePending = getJson(balance("B-CLOSE", "CD-CLOSE"), 200);
        assertEquals(FROZEN_CENTS, balancePending.get("frozenTaxCents").asLong());
        assertEquals(0, balancePending.get("occupiedCents").asLong());
        assertEquals(FROZEN_CENTS, balancePending.get("remainingCents").asLong());
        assertEquals(5000, balancePending.get("pendingCents").asLong());

        // 批准（复核人不同于提交人），版本 1→2，占用 5000
        JsonNode approved = postJson(review("B-CLOSE", "CORR-1"),
                TestFixtures.reversalReview("rev-rev-1", 1, "reviewer-b", true, null), 200);
        assertEquals("APPROVED", approved.get("status").asText());
        assertEquals(2, approved.get("version").asInt());
        assertEquals(5000, approved.get("approvedCents").asLong());
        assertEquals(5000, approved.get("occupiedCents").asLong());
        assertEquals(FROZEN_CENTS - 5000, approved.get("remainingCents").asLong());

        // 余额台账反映占用；待复核归零
        JsonNode balanceApproved = getJson(balance("B-CLOSE", "CD-CLOSE"), 200);
        assertEquals(5000, balanceApproved.get("occupiedCents").asLong());
        assertEquals(8000, balanceApproved.get("remainingCents").asLong());
        assertEquals(0, balanceApproved.get("pendingCents").asLong());

        // 第一笔部分到账 3000（乱序时刻：回执时刻早于第二笔），版本 2→3，仍 APPROVED
        JsonNode r1 = postJson(receipts("B-CLOSE", "CORR-1"),
                TestFixtures.reversalReceipt("rev-rcp-1", 2, "RCV-0001", 3000,
                        "2026-03-02T08:00:00Z"), 200);
        assertEquals("APPROVED", r1.get("status").asText());
        assertEquals(3, r1.get("version").asInt());
        assertEquals(3000, r1.get("collectedCents").asLong());

        // 第二笔到账 2000，恰收齐 -> COMPLETED，版本 3→4；时刻虽更早，总额不变
        JsonNode r2 = postJson(receipts("B-CLOSE", "CORR-1"),
                TestFixtures.reversalReceipt("rev-rcp-2", 3, "RCV-0002", 2000,
                        "2026-03-01T08:00:00Z"), 200);
        assertEquals("COMPLETED", r2.get("status").asText());
        assertEquals(4, r2.get("version").asInt());
        assertEquals(5000, r2.get("collectedCents").asLong());
        assertEquals(2, r2.get("receipts").size());

        // 结案批次仍 CLOSED，结案总额（退库）未被冲回改动
        JsonNode batch = getJson("/api/batches/B-CLOSE", 200);
        assertEquals("CLOSED", batch.get("status").asText());
        assertEquals(0, new java.math.BigDecimal("130.0000")
                .compareTo(new java.math.BigDecimal(batch.get("totalDeclaredTax").asText())));

        // 完成后只读：再登记回执 409
        JsonNode afterComplete = postJson(receipts("B-CLOSE", "CORR-1"),
                TestFixtures.reversalReceipt("rev-rcp-3", 4, "RCV-0003", 1,
                        "2026-03-03T08:00:00Z"), 409);
        assertTrue(afterComplete.get("error").asText().contains("只读"));
    }

    @Test
    void fullReversalToZeroIsLegalAndBatchStaysClosed() throws Exception {
        seedClosedBatch("B-ZERO", "CD-ZERO", "1000", "20");
        // 恰冲回全部冻结额
        postJson(reversals("B-ZERO"),
                TestFixtures.reversalSubmit("z-sub", "CD-ZERO", "CORR-Z",
                        "全量冲回", FROZEN_CENTS, "clerk-a"), 200);
        JsonNode approved = postJson(review("B-ZERO", "CORR-Z"),
                TestFixtures.reversalReview("z-rev", 1, "reviewer-b", true, null), 200);
        assertEquals(0, approved.get("remainingCents").asLong());

        // 一笔收齐即完成
        JsonNode done = postJson(receipts("B-ZERO", "CORR-Z"),
                TestFixtures.reversalReceipt("z-rcp", 2, "RCV-Z", FROZEN_CENTS,
                        "2026-03-05T00:00:00Z"), 200);
        assertEquals("COMPLETED", done.get("status").asText());
        assertEquals(FROZEN_CENTS, done.get("collectedCents").asLong());

        // 再对同一单提交冲回超过剩余（0）-> 422
        JsonNode over = postJson(reversals("B-ZERO"),
                TestFixtures.reversalSubmit("z-sub-2", "CD-ZERO", "CORR-Z2",
                        "超额", 1, "clerk-a"), 422);
        assertTrue(over.get("error").asText().contains("剩余可冲额"));
    }
}
