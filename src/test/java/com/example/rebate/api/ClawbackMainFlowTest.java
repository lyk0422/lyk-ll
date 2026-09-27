package com.example.rebate.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.rebate.testsupport.BaseApiTest;
import com.example.rebate.testsupport.TestFixtures;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

/**
 * 结案后分批冲回主流程：提交待复核 → 非提交人批准占用额度并产生追缴明细 →
 * 多笔部分到账恰收齐转完成；原批次保持结案，原文库版本/核销流水/结案总额不变。
 */
class ClawbackMainFlowTest extends BaseApiTest {

    private static final String BATCH = "B-CB-MAIN";
    private static final String DECL = "CD-CB-A";

    /** CD-CB-A: 1000×13%=130 元 = 13000 分；CD-CB-B: 500×13%=65 元 = 6500 分。 */
    private void prepareClosedBatch() throws Exception {
        seedLibrary();
        postJson("/api/collect/customs",
                TestFixtures.customs("cb-ca", DECL, TestFixtures.P1, "1000", "20"), 200);
        postJson("/api/collect/customs",
                TestFixtures.customs("cb-cb", "CD-CB-B", TestFixtures.P1, "500", "10"), 200);
        postJson("/api/collect/invoices",
                TestFixtures.invoice("cb-ia", "INV-CB-A", DECL, TestFixtures.P1, "900"), 200);
        postJson("/api/collect/invoices",
                TestFixtures.invoice("cb-ib", "INV-CB-B", "CD-CB-B", TestFixtures.P1, "450"), 200);
        postJson("/api/batches/check",
                TestFixtures.check("cb-chk", BATCH, "2026-02-01", DECL, "CD-CB-B"), 200);
        postJson("/api/batches/" + BATCH + "/declare",
                TestFixtures.declare("cb-decl", false), 200);
        postJson("/api/batches/" + BATCH + "/approve",
                TestFixtures.requestId("cb-appr"), 200);
        // 退库 195 元全额到账后结案（结案总额 195.0000，后续冲回不得改变）
        JsonNode wo = postJson("/api/batches/" + BATCH + "/treasury",
                TestFixtures.treasury("cb-w", "GK-CB", "195.00"), 200);
        assertEquals("TREASURED", wo.get("batchStatus").asText());
        postJson("/api/batches/" + BATCH + "/close",
                TestFixtures.requestId("cb-close"), 200);
    }

    @Test
    void submitReviewAndRecoverInPartsUntilComplete() throws Exception {
        prepareClosedBatch();

        // 提交冲回 8000 分（< 冻结 13000），初为 PENDING、版本 1、不占额
        JsonNode submit = postJson("/api/batches/" + BATCH + "/clawbacks",
                TestFixtures.clawbackSubmit("cb-sub1", DECL, "CORR-1",
                        "单证品名不符冲回", 8000L, "alice"), 200);
        long clawbackId = submit.get("clawback").get("id").asLong();
        assertEquals("PENDING", submit.get("clawback").get("status").asText());
        assertEquals(1, submit.get("clawback").get("version").asInt());
        assertTrue(submit.get("recoveryId").isNull());
        assertEquals(13000L, submit.get("frozenTaxCents").asLong());
        assertEquals(0L, submit.get("occupiedCents").asLong());
        assertEquals(13000L, submit.get("remainingCents").asLong());

        // 提交人不能复核自己
        JsonNode selfReview = postJson(
                "/api/batches/" + BATCH + "/clawbacks/" + clawbackId + "/review",
                TestFixtures.clawbackReview("cb-rev-self", 1, true, "alice"), 422);
        assertTrue(selfReview.get("error").asText().contains("不同于提交人"));

        // 非提交人 bob 批准：占用 8000、版本变 2、产生追缴明细
        JsonNode approved = postJson(
                "/api/batches/" + BATCH + "/clawbacks/" + clawbackId + "/review",
                TestFixtures.clawbackReview("cb-rev-ok", 1, true, "bob"), 200);
        assertEquals("APPROVED", approved.get("clawback").get("status").asText());
        assertEquals(2, approved.get("clawback").get("version").asInt());
        assertEquals("bob", approved.get("clawback").get("reviewer").asText());
        assertNotNull(approved.get("recoveryId"));
        assertEquals(8000L, approved.get("occupiedCents").asLong());
        assertEquals(5000L, approved.get("remainingCents").asLong());

        // 第一笔部分到账 3000 分：成功变更使版本 2 -> 3
        JsonNode r1 = postJson(
                "/api/batches/" + BATCH + "/clawbacks/" + clawbackId + "/receipts",
                TestFixtures.clawbackReceipt("cb-rc1", "RCV-1", 3000L,
                        "2026-03-01T10:00:00Z", 2), 200);
        assertEquals("RECOVERING", r1.get("status").asText());
        assertEquals(3000L, r1.get("receivedAmountCents").asLong());
        assertEquals(5000L, r1.get("remainingCents").asLong());
        assertEquals(3, r1.get("clawbackVersion").asInt());

        // 乱序到账：后发生的 5000 分先登记，总额不受顺序影响；版本 3 -> 4
        JsonNode r2 = postJson(
                "/api/batches/" + BATCH + "/clawbacks/" + clawbackId + "/receipts",
                TestFixtures.clawbackReceipt("cb-rc2", "RCV-2", 5000L,
                        "2026-03-02T09:00:00Z", 3), 200);
        assertEquals("COMPLETED", r2.get("status").asText());
        assertEquals(8000L, r2.get("receivedAmountCents").asLong());
        assertEquals(0L, r2.get("remainingCents").asLong());
        assertEquals(4, r2.get("clawbackVersion").asInt());

        // 完成后只读：再登记回执 422
        postJson(
                "/api/batches/" + BATCH + "/clawbacks/" + clawbackId + "/receipts",
                TestFixtures.clawbackReceipt("cb-rc3", "RCV-3", 1L,
                        "2026-03-03T09:00:00Z", 4), 422);

        // 逐单余额：冻结额不变，占用 8000，剩余 5000
        JsonNode balance = getJson(
                "/api/batches/" + BATCH + "/clawbacks/balance/" + DECL, 200);
        assertEquals("CLOSED", balance.get("batchStatus").asText());
        assertEquals(13000L, balance.get("frozenTaxCents").asLong());
        assertEquals(8000L, balance.get("occupiedCents").asLong());
        assertEquals(5000L, balance.get("remainingCents").asLong());
        assertEquals(1, balance.get("clawbacks").size());

        // 详情含两笔回执
        JsonNode detail = getJson(
                "/api/batches/" + BATCH + "/clawbacks/" + clawbackId, 200);
        assertEquals("COMPLETED", detail.get("clawback").get("status").asText());
        assertEquals(2, detail.get("recovery").get("receipts").size());

        // 原批次仍结案，结案总额与原核销流水均不被冲回改变
        JsonNode batch = getJson("/api/batches/" + BATCH, 200);
        assertEquals("CLOSED", batch.get("status").asText());
        assertEquals(0, new java.math.BigDecimal("195.0000")
                .compareTo(new java.math.BigDecimal(batch.get("totalDeclaredTax").asText())));
        JsonNode writeoffs = getJson("/api/batches/" + BATCH + "/treasury", 200);
        assertEquals(1, writeoffs.size());
        assertEquals("GK-CB", writeoffs.get(0).get("treasurySerial").asText());
    }

    @Test
    void rejectDoesNotOccupyQuota() throws Exception {
        prepareClosedBatch();
        JsonNode submit = postJson("/api/batches/" + BATCH + "/clawbacks",
                TestFixtures.clawbackSubmit("cb-sub-r", DECL, "CORR-R",
                        "存疑冲回", 13000L, "alice"), 200);
        long id = submit.get("clawback").get("id").asLong();

        JsonNode rejected = postJson(
                "/api/batches/" + BATCH + "/clawbacks/" + id + "/review",
                TestFixtures.clawbackReview("cb-rev-r", 1, false, "bob"), 200);
        assertEquals("REJECTED", rejected.get("clawback").get("status").asText());
        assertEquals(2, rejected.get("clawback").get("version").asInt());
        assertTrue(rejected.get("recoveryId").isNull());
        assertEquals(0L, rejected.get("occupiedCents").asLong());
        assertEquals(13000L, rejected.get("remainingCents").asLong());

        // 已驳回不能登记回执
        postJson(
                "/api/batches/" + BATCH + "/clawbacks/" + id + "/receipts",
                TestFixtures.clawbackReceipt("cb-rc-r", "RCV-R", 1L,
                        "2026-03-01T10:00:00Z", 2), 422);
    }
}
