package com.example.rebate.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.rebate.testsupport.BaseApiTest;
import com.example.rebate.testsupport.TestFixtures;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

/**
 * 冲回失败分支与 HTTP 语义：参数非法 400、对象不存在 404、业务拒绝 422、版本/幂等冲突 409。
 */
class ClawbackFailureTest extends BaseApiTest {

    private static final String BATCH = "B-CB-FAIL";
    private static final String DECL = "CD-CF";

    /** CD-CF: 1000×13%=130 元 = 13000 分。 */
    private String buildBatch(boolean close) throws Exception {
        seedLibrary();
        postJson("/api/collect/customs",
                TestFixtures.customs("cf-c", DECL, TestFixtures.P1, "1000", "20"), 200);
        postJson("/api/collect/invoices",
                TestFixtures.invoice("cf-i", "INV-CF", DECL, TestFixtures.P1, "900"), 200);
        postJson("/api/batches/check",
                TestFixtures.check("cf-chk", BATCH, "2026-02-01", DECL), 200);
        postJson("/api/batches/" + BATCH + "/declare",
                TestFixtures.declare("cf-decl", false), 200);
        postJson("/api/batches/" + BATCH + "/approve",
                TestFixtures.requestId("cf-appr"), 200);
        if (close) {
            postJson("/api/batches/" + BATCH + "/treasury",
                    TestFixtures.treasury("cf-w", "GK-CF", "130.00"), 200);
            postJson("/api/batches/" + BATCH + "/close",
                    TestFixtures.requestId("cf-close"), 200);
        }
        return BATCH;
    }

    private long submitClawback(String reqId, String corrNo, long cents) throws Exception {
        JsonNode node = postJson("/api/batches/" + BATCH + "/clawbacks",
                TestFixtures.clawbackSubmit(reqId, DECL, corrNo, "原因", cents, "alice"), 200);
        return node.get("clawback").get("id").asLong();
    }

    @Test
    void invalidParamsReturn400() throws Exception {
        buildBatch(true);
        // 金额非正
        postJson("/api/batches/" + BATCH + "/clawbacks",
                TestFixtures.clawbackSubmit("f-zero", DECL, "C0", "原因", 0L, "alice"), 400);
        // 金额缺失
        postJson("/api/batches/" + BATCH + "/clawbacks",
                """
                {"requestId":"f-null","declarationNo":"%s","externalCorrectionNo":"C0N",
                 "reason":"原因","submittedBy":"alice"}
                """.formatted(DECL), 400);
        // 原因为空
        postJson("/api/batches/" + BATCH + "/clawbacks",
                TestFixtures.clawbackSubmit("f-blank", DECL, "C0B", "  ", 100L, "alice"), 400);
        // 到账时刻非法
        long id = submitClawback("f-cb", "C1", 100L);
        postJson("/api/batches/" + BATCH + "/clawbacks/" + id + "/review",
                TestFixtures.clawbackReview("f-rv", 1, true, "bob"), 200);
        postJson(
                "/api/batches/" + BATCH + "/clawbacks/" + id + "/receipts",
                """
                {"requestId":"f-badtime","serialNo":"S1","amountCents":100,
                 "receivedAt":"not-an-instant","expectedVersion":2}
                """, 400);
        // 复核缺少 expectedVersion
        long id2 = submitClawback("f-cb2", "C2", 100L);
        postJson(
                "/api/batches/" + BATCH + "/clawbacks/" + id2 + "/review",
                """
                {"requestId":"f-noversion","approved":true,"reviewer":"bob"}
                """, 400);
    }

    @Test
    void notFoundReturn404() throws Exception {
        buildBatch(true);
        // 批次不存在
        postJson("/api/batches/NO-SUCH/clawbacks",
                TestFixtures.clawbackSubmit("nf-batch", DECL, "CX", "原因", 100L, "alice"), 404);
        // 报关单不在批次内
        postJson("/api/batches/" + BATCH + "/clawbacks",
                TestFixtures.clawbackSubmit("nf-decl", "CD-ELSE", "CX", "原因", 100L, "alice"),
                404);
        // 冲回单不存在
        postJson("/api/batches/" + BATCH + "/clawbacks/999999/review",
                TestFixtures.clawbackReview("nf-cb", 1, true, "bob"), 404);
        // 余额查询的报关单不存在
        getJson("/api/batches/" + BATCH + "/clawbacks/balance/CD-ELSE", 404);
    }

    @Test
    void businessRejectionsReturn422() throws Exception {
        // 批次尚未结案：不能冲回
        buildBatch(false);
        postJson("/api/batches/" + BATCH + "/clawbacks",
                TestFixtures.clawbackSubmit("u-open", DECL, "CO", "原因", 100L, "alice"), 422);
        getJson("/api/batches/" + BATCH + "/clawbacks/balance/" + DECL, 422);

        // 结案后继续
        postJson("/api/batches/" + BATCH + "/treasury",
                TestFixtures.treasury("u-w", "GK-U", "130.00"), 200);
        postJson("/api/batches/" + BATCH + "/close",
                TestFixtures.requestId("u-close"), 200);

        // 恰归零合法：批准全额 13000
        long fullId = submitClawback("u-full", "CFULL", 13000L);
        JsonNode full = postJson("/api/batches/" + BATCH + "/clawbacks/" + fullId + "/review",
                TestFixtures.clawbackReview("u-full-r", 1, true, "bob"), 200);
        assertEquals(0L, full.get("remainingCents").asLong());

        // 再批准任何正金额必超冻结
        long extraId = submitClawback("u-extra", "CEXTRA", 1L);
        JsonNode exceed = postJson(
                "/api/batches/" + BATCH + "/clawbacks/" + extraId + "/review",
                TestFixtures.clawbackReview("u-extra-r", 1, true, "bob"), 422);
        assertTrue(exceed.get("error").asText().contains("超过冻结退税额"));
        // 超冲事务回滚：待复核单仍不占额，占用不增加
        JsonNode balance = getJson(
                "/api/batches/" + BATCH + "/clawbacks/balance/" + DECL, 200);
        assertEquals(13000L, balance.get("occupiedCents").asLong());
        assertEquals(0L, balance.get("remainingCents").asLong());

        // 未批准（待复核）不能登记回执
        postJson(
                "/api/batches/" + BATCH + "/clawbacks/" + extraId + "/receipts",
                TestFixtures.clawbackReceipt("u-pend-rc", "SP", 1L,
                        "2026-03-01T10:00:00Z", 1), 422);

        // 重复复核已批准单 422
        postJson("/api/batches/" + BATCH + "/clawbacks/" + fullId + "/review",
                TestFixtures.clawbackReview("u-again", 2, false, "carol"), 422);
    }

    @Test
    void receiptOverApprovedAndDuplicateRules() throws Exception {
        buildBatch(true);
        long id = submitClawback("ro-cb", "CRO", 1000L);
        postJson("/api/batches/" + BATCH + "/clawbacks/" + id + "/review",
                TestFixtures.clawbackReview("ro-rv", 1, true, "bob"), 200);

        // 首笔 600 成功后版本 2 -> 3；第二笔 500 将累计 1100 > 1000 -> 422，且不占账
        postJson(
                "/api/batches/" + BATCH + "/clawbacks/" + id + "/receipts",
                TestFixtures.clawbackReceipt("ro-1", "SR1", 600L,
                        "2026-03-01T10:00:00Z", 2), 200);
        JsonNode over = postJson(
                "/api/batches/" + BATCH + "/clawbacks/" + id + "/receipts",
                TestFixtures.clawbackReceipt("ro-2", "SR2", 500L,
                        "2026-03-02T10:00:00Z", 3), 422);
        assertTrue(over.get("error").asText().contains("超过批准金额"));

        // 超收回滚：SR2 不存在，累计仍 600
        JsonNode detail = getJson(
                "/api/batches/" + BATCH + "/clawbacks/" + id, 200);
        assertEquals(600L, detail.get("recovery").get("receivedAmountCents").asLong());
        assertEquals(1, detail.get("recovery").get("receipts").size());

        // 同流水异金额 409
        JsonNode dup = postJson(
                "/api/batches/" + BATCH + "/clawbacks/" + id + "/receipts",
                TestFixtures.clawbackReceipt("ro-3", "SR1", 601L,
                        "2026-03-01T10:00:00Z", 3), 409);
        assertTrue(dup.get("error").asText().contains("已用于不同金额"));

        // 版本不匹配 409
        postJson(
                "/api/batches/" + BATCH + "/clawbacks/" + id + "/receipts",
                TestFixtures.clawbackReceipt("ro-4", "SR3", 400L,
                        "2026-03-03T10:00:00Z", 99), 409);

        // 恰收齐（600+400=1000）转完成；首笔成功后版本已 2 -> 3，故此处期望 3
        JsonNode done = postJson(
                "/api/batches/" + BATCH + "/clawbacks/" + id + "/receipts",
                TestFixtures.clawbackReceipt("ro-5", "SR3", 400L,
                        "2026-03-03T10:00:00Z", 3), 200);
        assertEquals("COMPLETED", done.get("status").asText());
        assertEquals(4, done.get("clawbackVersion").asInt());

        // 同批次外部更正编号唯一
        postJson("/api/batches/" + BATCH + "/clawbacks",
                TestFixtures.clawbackSubmit("ro-dup", DECL, "CRO", "重复", 100L, "alice"),
                409);
    }
}
