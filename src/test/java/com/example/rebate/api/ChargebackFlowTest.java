package com.example.rebate.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.rebate.testsupport.BaseApiTest;
import com.example.rebate.testsupport.TestFixtures;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

/**
 * 结案后分批冲回主流程：提交待复核 → 不同复核人批准（占用余额、产生追缴明细）→
 * 多笔部分到账恰收齐转完成；驳回不占额；逐单余额查询；原批次保持结案。
 */
class ChargebackFlowTest extends BaseApiTest {

    /** 1000×13%=130.00 → 冻结 13000 分。 */
    private String prepareClosedBatch(String batchNo, String declNo, String prefix) throws Exception {
        seedLibrary();
        postJson("/api/collect/customs",
                TestFixtures.customs(prefix + "-c", declNo, TestFixtures.P1, "1000", "20"), 200);
        postJson("/api/collect/invoices",
                TestFixtures.invoice(prefix + "-i", "INV-" + declNo, declNo,
                        TestFixtures.P1, "900"), 200);
        postJson("/api/batches/check",
                TestFixtures.check(prefix + "-chk", batchNo, "2026-02-01", declNo), 200);
        postJson("/api/batches/" + batchNo + "/declare",
                TestFixtures.declare(prefix + "-decl", false), 200);
        postJson("/api/batches/" + batchNo + "/approve",
                TestFixtures.requestId(prefix + "-appr"), 200);
        postJson("/api/batches/" + batchNo + "/treasury",
                TestFixtures.treasury(prefix + "-w", "GK-" + prefix, "130.00"), 200);
        postJson("/api/batches/" + batchNo + "/close",
                TestFixtures.requestId(prefix + "-close"), 200);
        return batchNo;
    }

    @Test
    void submitApproveAndCollectInPartsUntilCompleted() throws Exception {
        String b = prepareClosedBatch("B-CB1", "CD-CB1", "cb1");

        // 提交冲回 5000 分，初版 PENDING version=1
        JsonNode submitted = postJson("/api/batches/B-CB1/chargebacks",
                TestFixtures.chargebackSubmit("cb1-sub", "CD-CB1", "COR-1",
                        "多收退税款，分批冲回", 5000L, "alice"), 200);
        assertEquals("PENDING", submitted.get("status").asText());
        assertEquals(1, submitted.get("version").asInt());
        assertEquals(13000L, submitted.get("frozenCents").asLong());
        assertEquals(0L, submitted.get("occupiedCents").asLong());
        assertEquals(13000L, submitted.get("remainingCents").asLong());
        assertTrue(submitted.get("clawback").isNull(), "待复核不产生追缴明细");

        // 提交人本人不能复核
        postJson("/api/batches/B-CB1/chargebacks/COR-1/review",
                TestFixtures.chargebackReview("cb1-self", 1, "APPROVE", "alice"), 422);

        // 另一复核人批准 expectedVersion=1 → version=2，占用 5000，产生追缴明细
        JsonNode approved = postJson("/api/batches/B-CB1/chargebacks/COR-1/review",
                TestFixtures.chargebackReview("cb1-appr", 1, "APPROVE", "bob"), 200);
        assertEquals("APPROVED", approved.get("status").asText());
        assertEquals(2, approved.get("version").asInt());
        assertEquals("bob", approved.get("reviewer").asText());
        assertEquals(5000L, approved.get("occupiedCents").asLong());
        assertEquals(8000L, approved.get("remainingCents").asLong());
        JsonNode clawback = approved.get("clawback");
        assertNotNull(clawback);
        assertEquals(5000L, clawback.get("approvedCents").asLong());
        assertEquals(0L, clawback.get("collectedCents").asLong());
        assertEquals("COLLECTING", clawback.get("status").asText());
        assertEquals(1, clawback.get("version").asInt());
        assertTrue(clawback.get("clawbackNo").asText().startsWith("CLW-"));

        // 部分到账 3000，追缴明细 version 1→2，仍追缴中
        JsonNode r1 = postJson("/api/batches/B-CB1/chargebacks/COR-1/receipts",
                TestFixtures.receipt("cb1-r1", 1, "RCV-1", 3000L, "2026-03-01T10:00:00Z"), 200);
        JsonNode cb1 = r1.get("clawback");
        assertEquals(3000L, cb1.get("collectedCents").asLong());
        assertEquals(2000L, cb1.get("remainingCents").asLong());
        assertEquals("COLLECTING", cb1.get("status").asText());
        assertEquals(2, cb1.get("version").asInt());

        // 余额 2000 恰好到账 → COMPLETED，version 2→3
        JsonNode r2 = postJson("/api/batches/B-CB1/chargebacks/COR-1/receipts",
                TestFixtures.receipt("cb1-r2", 2, "RCV-2", 2000L, "2026-03-02T10:00:00Z"), 200);
        JsonNode cb2 = r2.get("clawback");
        assertEquals(5000L, cb2.get("collectedCents").asLong());
        assertEquals(0L, cb2.get("remainingCents").asLong());
        assertEquals("COMPLETED", cb2.get("status").asText());
        assertEquals(3, cb2.get("version").asInt());
        assertEquals(2, cb2.get("receipts").size());

        // 完成后回执只读
        postJson("/api/batches/B-CB1/chargebacks/COR-1/receipts",
                TestFixtures.receipt("cb1-r3", 3, "RCV-3", 1L, "2026-03-03T10:00:00Z"), 422);

        // 逐单余额查询：冻结 13000、占用 5000、剩余 8000
        JsonNode balances = getJson("/api/batches/B-CB1/chargebacks", 200);
        assertEquals(1, balances.size());
        assertEquals("CD-CB1", balances.get(0).get("declarationNo").asText());
        assertEquals(13000L, balances.get(0).get("frozenCents").asLong());
        assertEquals(5000L, balances.get(0).get("occupiedCents").asLong());
        assertEquals(8000L, balances.get(0).get("remainingCents").asLong());

        // 单笔查询一致
        JsonNode one = getJson("/api/batches/B-CB1/chargebacks/COR-1", 200);
        assertEquals("APPROVED", one.get("status").asText());
        assertEquals("COMPLETED", one.get("clawback").get("status").asText());

        // 原批次仍结案，结案总额未被改动
        JsonNode batch = getJson("/api/batches/B-CB1", 200);
        assertEquals("CLOSED", batch.get("status").asText());
        assertEquals(0, new java.math.BigDecimal("130.0000")
                .compareTo(batch.get("totalDeclaredTax").decimalValue()));
    }

    @Test
    void rejectDoesNotOccupyAndApprovalReplayKeepsFrozenResult() throws Exception {
        String b = prepareClosedBatch("B-CB2", "CD-CB2", "cb2");

        postJson("/api/batches/B-CB2/chargebacks",
                TestFixtures.chargebackSubmit("cb2-sub", "CD-CB2", "COR-R",
                        "理由含分隔符|与,特殊;字符", 4000L, "carol"), 200);

        // 驳回（expectedVersion=1）→ REJECTED version=2，不占额
        JsonNode rejected = postJson("/api/batches/B-CB2/chargebacks/COR-R/review",
                TestFixtures.chargebackReview("cb2-rej", 1, "REJECT", "dave"), 200);
        assertEquals("REJECTED", rejected.get("status").asText());
        assertEquals(2, rejected.get("version").asInt());
        assertEquals(0L, rejected.get("occupiedCents").asLong());
        assertEquals(13000L, rejected.get("remainingCents").asLong());
        assertTrue(rejected.get("clawback").isNull(), "驳回不产生追缴明细");

        // 驳回后不可再复核，也不能登记回执
        postJson("/api/batches/B-CB2/chargebacks/COR-R/review",
                TestFixtures.chargebackReview("cb2-rej2", 2, "APPROVE", "dave"), 409);
        postJson("/api/batches/B-CB2/chargebacks/COR-R/receipts",
                TestFixtures.receipt("cb2-rc", 1, "RCV-X", 100L, "2026-03-01T10:00:00Z"), 422);

        // 成功驳回重放（同键同参）回放首次冻结结果，不重新计算余额
        JsonNode replay = postJson("/api/batches/B-CB2/chargebacks/COR-R/review",
                TestFixtures.chargebackReview("cb2-rej", 1, "REJECT", "dave"), 200);
        assertEquals("REJECTED", replay.get("status").asText());
        assertEquals(2, replay.get("version").asInt());
        assertEquals(0L, replay.get("occupiedCents").asLong());

        JsonNode balances = getJson("/api/batches/B-CB2/chargebacks", 200);
        assertEquals(0L, balances.get(0).get("occupiedCents").asLong());
    }

    @Test
    void exactZeroRemainingIsLegalAcrossBatches() throws Exception {
        String b = prepareClosedBatch("B-CB3", "CD-CB3", "cb3");

        postJson("/api/batches/B-CB3/chargebacks",
                TestFixtures.chargebackSubmit("cb3-s1", "CD-CB3", "COR-A", "首批", 7000L,
                        "erin"), 200);
        postJson("/api/batches/B-CB3/chargebacks/COR-A/review",
                TestFixtures.chargebackReview("cb3-a", 1, "APPROVE", "frank"), 200);

        // 剩余 6000，第二笔恰好冲平合法
        postJson("/api/batches/B-CB3/chargebacks",
                TestFixtures.chargebackSubmit("cb3-s2", "CD-CB3", "COR-B", "冲平", 6000L,
                        "erin"), 200);
        JsonNode approvedB = postJson("/api/batches/B-CB3/chargebacks/COR-B/review",
                TestFixtures.chargebackReview("cb3-b", 1, "APPROVE", "frank"), 200);
        assertEquals(13000L, approvedB.get("occupiedCents").asLong());
        assertEquals(0L, approvedB.get("remainingCents").asLong());
    }
}
