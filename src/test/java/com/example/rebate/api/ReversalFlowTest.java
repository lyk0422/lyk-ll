package com.example.rebate.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.rebate.testsupport.BaseApiTest;
import com.example.rebate.testsupport.TestFixtures;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

/**
 * 结案批次分批冲回主流程与失败分支：
 * 提交→复核（批准/驳回）→多笔追缴到账→完成，版本递增、余额台账、错误语义与幂等冻结结果。
 */
class ReversalFlowTest extends BaseApiTest {

    private static final String BATCH = "B-RV";
    private static final String DECL = "CD-RV";

    /** 完整追缴：部分到账两笔恰收齐；结案总额/核销流水不变。 */
    @Test
    void submitApprovePartialReceiptsThenComplete() throws Exception {
        closedSingleBatch(BATCH, DECL);

        JsonNode balance0 = getJson(
                "/api/batches/" + BATCH + "/declarations/" + DECL + "/reversal-balance", 200);
        assertEquals(13000L, balance0.get("frozenCents").asLong());
        assertEquals(13000L, balance0.get("availableCents").asLong());
        assertEquals(1, balance0.get("versionNo").asInt());

        JsonNode submitted = postJson("/api/batches/" + BATCH + "/reversals",
                TestFixtures.reversalSubmit("rv-sub-1", DECL, "COR-1",
                        "商品归类调整，冲回多退税款", 5000L, "alice", 1,
                        "2026-03-10T10:00:00Z"), 200);
        long reversalId = submitted.get("id").asLong();
        assertEquals("PENDING", submitted.get("status").asText());
        assertEquals(1, submitted.get("versionNo").asInt());
        assertNull(submitted.get("clawbackSerial").asText(null));
        assertEquals(13000L, submitted.get("remainingAvailableCents").asLong());

        // 复核人必须不同于提交人
        postJson("/api/batches/" + BATCH + "/reversals/" + reversalId + "/review",
                TestFixtures.reversalReview("rv-rev-bad", "alice", "APPROVE", 1,
                        "2026-03-10T11:00:00Z"), 422);
        // 错误版本 409
        postJson("/api/batches/" + BATCH + "/reversals/" + reversalId + "/review",
                TestFixtures.reversalReview("rv-rev-ver", "bob", "APPROVE", 9,
                        "2026-03-10T11:00:00Z"), 409);

        JsonNode approved = postJson("/api/batches/" + BATCH + "/reversals/" + reversalId + "/review",
                TestFixtures.reversalReview("rv-rev-1", "bob", "APPROVE", 1,
                        "2026-03-10T11:00:00Z"), 200);
        assertEquals("APPROVED", approved.get("status").asText());
        assertEquals(2, approved.get("versionNo").asInt());
        assertEquals("bob", approved.get("reviewer").asText());
        assertTrue(approved.get("clawbackSerial").asText().startsWith("CL"),
                "批准必须产生独立追缴流水号");
        assertEquals(8000L, approved.get("remainingAvailableCents").asLong());
        assertEquals(2, approved.get("ledgerVersion").asInt());

        JsonNode balance1 = getJson(
                "/api/batches/" + BATCH + "/declarations/" + DECL + "/reversal-balance", 200);
        assertEquals(5000L, balance1.get("occupiedCents").asLong());
        assertEquals(8000L, balance1.get("availableCents").asLong());
        assertEquals(0L, balance1.get("receivedCents").asLong());
        assertEquals(2, balance1.get("versionNo").asInt());

        // 未批准语义之外：已批准单登记第一笔部分到账 3000（批准后版本为 2）
        JsonNode r1 = postJson(
                "/api/batches/" + BATCH + "/reversals/" + reversalId + "/receipts",
                TestFixtures.clawbackReceipt("rv-rc-1", "RC-0001", 3000L, 2,
                        "2026-03-11T09:00:00Z"), 200);
        assertEquals("APPROVED", r1.get("status").asText());
        assertEquals(3, r1.get("versionNo").asInt());
        assertEquals(3000L, r1.get("receivedCents").asLong());
        assertEquals(2000L, r1.get("receivableRemainingCents").asLong());

        // 超收拒绝：再登记 3000 将累计 6000 > 5000
        JsonNode over = postJson(
                "/api/batches/" + BATCH + "/reversals/" + reversalId + "/receipts",
                TestFixtures.clawbackReceipt("rv-rc-over", "RC-000X", 3000L, 3,
                        "2026-03-11T10:00:00Z"), 422);
        assertTrue(over.get("error").asText().contains("超过批准金额"));

        // 恰收齐 2000 → COMPLETED
        JsonNode done = postJson(
                "/api/batches/" + BATCH + "/reversals/" + reversalId + "/receipts",
                TestFixtures.clawbackReceipt("rv-rc-2", "RC-0002", 2000L, 3,
                        "2026-03-11T11:00:00Z"), 200);
        assertEquals("COMPLETED", done.get("status").asText());
        assertEquals(4, done.get("versionNo").asInt());
        assertEquals(0L, done.get("receivableRemainingCents").asLong());

        // 完成后只读：再登记回执 422
        postJson("/api/batches/" + BATCH + "/reversals/" + reversalId + "/receipts",
                TestFixtures.clawbackReceipt("rv-rc-ro", "RC-0003", 1L, 4,
                        "2026-03-11T12:00:00Z"), 422);

        JsonNode receipts = getJson(
                "/api/batches/" + BATCH + "/reversals/" + reversalId + "/receipts", 200);
        assertEquals(2, receipts.size());
        assertEquals("RC-0001", receipts.get(0).get("receiptSerial").asText());

        // 批次仍结案，结案总额与核销流水不变
        JsonNode batch = getJson("/api/batches/" + BATCH, 200);
        assertEquals("CLOSED", batch.get("status").asText());
        assertEquals(0, new java.math.BigDecimal("130.0000")
                .compareTo(new java.math.BigDecimal(batch.get("totalDeclaredTax").asText())));
        JsonNode writeoffs = getJson("/api/batches/" + BATCH + "/treasury", 200);
        assertEquals(1, writeoffs.size());
        assertEquals(0, new java.math.BigDecimal("130.00")
                .compareTo(new java.math.BigDecimal(writeoffs.get(0).get("receivedAmount").asText())));

        JsonNode balanceEnd = getJson(
                "/api/batches/" + BATCH + "/declarations/" + DECL + "/reversal-balance", 200);
        assertEquals(5000L, balanceEnd.get("receivedCents").asLong());
        assertEquals(5000L, balanceEnd.get("occupiedCents").asLong());
        assertEquals(8000L, balanceEnd.get("availableCents").asLong());
    }

    /** 驳回不占额；之后全额冲回仍可批准且恰归零合法。 */
    @Test
    void rejectDoesNotOccupyAndExactZeroIsAllowed() throws Exception {
        closedSingleBatch(BATCH, DECL);

        JsonNode rejected = postJson("/api/batches/" + BATCH + "/reversals",
                TestFixtures.reversalSubmit("rv-sub-r", DECL, "COR-R",
                        "驳回用申请", 13000L, "alice", 1, "2026-03-10T10:00:00Z"), 200);
        long rejectedId = rejected.get("id").asLong();
        JsonNode rj = postJson("/api/batches/" + BATCH + "/reversals/" + rejectedId + "/review",
                TestFixtures.reversalReview("rv-rev-r", "bob", "REJECT", 1,
                        "2026-03-10T11:00:00Z"), 200);
        assertEquals("REJECTED", rj.get("status").asText());
        assertEquals(2, rj.get("versionNo").asInt());
        assertNull(rj.get("clawbackSerial").asText(null));

        JsonNode balance = getJson(
                "/api/batches/" + BATCH + "/declarations/" + DECL + "/reversal-balance", 200);
        assertEquals(0L, balance.get("occupiedCents").asLong());
        assertEquals(13000L, balance.get("availableCents").asLong());

        // 驳回单不能登记回执、不能再次复核
        postJson("/api/batches/" + BATCH + "/reversals/" + rejectedId + "/receipts",
                TestFixtures.clawbackReceipt("rv-rc-r", "RC-R1", 1L, 2,
                        "2026-03-11T09:00:00Z"), 422);
        postJson("/api/batches/" + BATCH + "/reversals/" + rejectedId + "/review",
                TestFixtures.reversalReview("rv-rev-r2", "bob", "APPROVE", 2,
                        "2026-03-11T09:30:00Z"), 422);

        // 恰归零：7000 + 6000 = 13000
        long id1 = postJson("/api/batches/" + BATCH + "/reversals",
                TestFixtures.reversalSubmit("rv-sub-a", DECL, "COR-A",
                        "分批一", 7000L, "carol", 1, "2026-03-12T10:00:00Z"), 200)
                .get("id").asLong();
        postJson("/api/batches/" + BATCH + "/reversals/" + id1 + "/review",
                TestFixtures.reversalReview("rv-rev-a", "dave", "APPROVE", 1,
                        "2026-03-12T11:00:00Z"), 200);
        long id2 = postJson("/api/batches/" + BATCH + "/reversals",
                TestFixtures.reversalSubmit("rv-sub-b", DECL, "COR-B",
                        "分批二", 6000L, "carol", 1, "2026-03-12T12:00:00Z"), 200)
                .get("id").asLong();
        JsonNode app2 = postJson("/api/batches/" + BATCH + "/reversals/" + id2 + "/review",
                TestFixtures.reversalReview("rv-rev-b", "dave", "APPROVE", 1,
                        "2026-03-12T13:00:00Z"), 200);
        assertEquals(0L, app2.get("remainingAvailableCents").asLong());

        // 归零后再申请任何正数金额均被拒
        postJson("/api/batches/" + BATCH + "/reversals",
                TestFixtures.reversalSubmit("rv-sub-c", DECL, "COR-C",
                        "超额", 1L, "carol", 1, "2026-03-12T14:00:00Z"), 422);
    }

    /** 待复核额度也参与累计预检；已批准后剩余不足的新申请拒绝。 */
    @Test
    void pendingAndApprovedBothCountAgainstFrozen() throws Exception {
        closedSingleBatch(BATCH, DECL);

        long id1 = postJson("/api/batches/" + BATCH + "/reversals",
                TestFixtures.reversalSubmit("rv-p-1", DECL, "COR-P1",
                        "待复核一", 7000L, "alice", 1, "2026-03-10T10:00:00Z"), 200)
                .get("id").asLong();
        // 尚有 6000 待复核 + 0 占用，申请 7000 → 422
        postJson("/api/batches/" + BATCH + "/reversals",
                TestFixtures.reversalSubmit("rv-p-2", DECL, "COR-P2",
                        "超额待复核", 7000L, "alice", 1, "2026-03-10T10:05:00Z"), 422);
        // 6000 恰可提交
        long id2 = postJson("/api/batches/" + BATCH + "/reversals",
                TestFixtures.reversalSubmit("rv-p-3", DECL, "COR-P3",
                        "恰满", 6000L, "alice", 1, "2026-03-10T10:06:00Z"), 200)
                .get("id").asLong();
        // 并发无关：批准第一张后，第二张剩余可冲额恰好为 6000，仍可批准
        postJson("/api/batches/" + BATCH + "/reversals/" + id1 + "/review",
                TestFixtures.reversalReview("rv-p-r1", "bob", "APPROVE", 1,
                        "2026-03-10T11:00:00Z"), 200);
        JsonNode app2 = postJson("/api/batches/" + BATCH + "/reversals/" + id2 + "/review",
                TestFixtures.reversalReview("rv-p-r2", "bob", "APPROVE", 1,
                        "2026-03-10T11:05:00Z"), 200);
        assertEquals("APPROVED", app2.get("status").asText());
        assertEquals(0L, app2.get("remainingAvailableCents").asLong());
    }

    /** 404/422/400 与同批次更正编号唯一等失败分支。 */
    @Test
    void invalidRequestsAreRejectedWithCorrectStatus() throws Exception {
        closedSingleBatch(BATCH, DECL);

        // 批次不存在 404
        postJson("/api/batches/NO-SUCH-BATCH/reversals",
                TestFixtures.reversalSubmit("rv-404-b", DECL, "X", "r", 1L, "a", 1,
                        "2026-03-10T10:00:00Z"), 404);
        // 批次内报关单不存在 404
        postJson("/api/batches/" + BATCH + "/reversals",
                TestFixtures.reversalSubmit("rv-404-d", "CD-NOPE", "X", "r", 1L, "a", 1,
                        "2026-03-10T10:00:00Z"), 404);
        // 金额必须为正数整数分：0 → 400
        postJson("/api/batches/" + BATCH + "/reversals",
                TestFixtures.reversalSubmit("rv-400-amt", DECL, "X", "r", 0L, "a", 1,
                        "2026-03-10T10:00:00Z"), 400);
        // 初始版本必须为 1 → 422
        postJson("/api/batches/" + BATCH + "/reversals",
                TestFixtures.reversalSubmit("rv-bad-ver", DECL, "X", "r", 1L, "a", 2,
                        "2026-03-10T10:00:00Z"), 422);
        // 非法 action → 400（@Pattern）
        long id = postJson("/api/batches/" + BATCH + "/reversals",
                TestFixtures.reversalSubmit("rv-x", DECL, "COR-X",
                        "r", 1L, "a", 1, "2026-03-10T10:00:00Z"), 200)
                .get("id").asLong();
        postJson("/api/batches/" + BATCH + "/reversals/" + id + "/review",
                TestFixtures.reversalReview("rv-bad-action", "b", "MAYBE", 1,
                        "2026-03-10T11:00:00Z"), 400);
        // 冲回单不存在 / 不属于该批次 404
        getJson("/api/batches/" + BATCH + "/reversals/999999", 404);
        closedSingleBatch("B-OTHER", "CD-OTHER");
        getJson("/api/batches/B-OTHER/reversals/" + id, 404);

        // 同批次同更正编号最多一单
        postJson("/api/batches/" + BATCH + "/reversals",
                TestFixtures.reversalSubmit("rv-dup-1", DECL, "COR-DUP",
                        "首次", 100L, "a", 1, "2026-03-10T12:00:00Z"), 200);
        JsonNode dup = postJson("/api/batches/" + BATCH + "/reversals",
                TestFixtures.reversalSubmit("rv-dup-2", DECL, "COR-DUP",
                        "再次", 100L, "a", 1, "2026-03-10T12:01:00Z"), 422);
        assertTrue(dup.get("error").asText().contains("更正编号已存在"));
        // 不同批次允许复用同一更正编号
        postJson("/api/batches/B-OTHER/reversals",
                TestFixtures.reversalSubmit("rv-dup-3", "CD-OTHER", "COR-DUP",
                        "他批", 100L, "a", 1, "2026-03-10T12:02:00Z"), 200);
    }

    /** 仅结案批次可冲回；退库后结案前提交 422。 */
    @Test
    void nonClosedBatchCannotReverse() throws Exception {
        seedLibrary();
        postJson("/api/collect/customs",
                TestFixtures.customs("c-open", "CD-OPEN", TestFixtures.P1, "1000", "20"), 200);
        postJson("/api/collect/invoices",
                TestFixtures.invoice("i-open", "INV-OPEN", "CD-OPEN", TestFixtures.P1, "1"), 200);
        postJson("/api/batches/check",
                TestFixtures.check("chk-open", "B-OPEN", "2026-02-01", "CD-OPEN"), 200);
        postJson("/api/batches/B-OPEN/declare",
                TestFixtures.declare("d-open", false), 200);
        postJson("/api/batches/B-OPEN/approve",
                TestFixtures.requestId("a-open"), 200);
        postJson("/api/batches/B-OPEN/treasury",
                TestFixtures.treasury("t-open", "GK-OPEN", "130.00"), 200);
        // TREASURED 但未结案
        postJson("/api/batches/B-OPEN/reversals",
                TestFixtures.reversalSubmit("rv-open", "CD-OPEN", "COR-O",
                        "未结案冲回", 1L, "a", 1, "2026-03-10T10:00:00Z"), 422);
    }

    /** 同键同参重放首次冻结结果（含后续余额已变化）；同键异参 409；失败不占键。 */
    @Test
    void idempotentReplayKeepsFirstFrozenResultAndFailureReleasesKey() throws Exception {
        closedSingleBatch(BATCH, DECL);

        String submitBody = TestFixtures.reversalSubmit("rv-idem", DECL, "COR-IDEM",
                "幂等申请", 5000L, "alice", 1, "2026-03-10T10:00:00Z");
        JsonNode first = postJson("/api/batches/" + BATCH + "/reversals", submitBody, 200);
        long reversalId = first.get("id").asLong();
        JsonNode replaySubmit = postJson("/api/batches/" + BATCH + "/reversals", submitBody, 200);
        assertEquals(first.get("id").asLong(), replaySubmit.get("id").asLong());

        // 批准使余额变化后，重放提交仍返回首次冻结结果：PENDING、剩余可冲 13000
        JsonNode approved = postJson("/api/batches/" + BATCH + "/reversals/" + reversalId + "/review",
                TestFixtures.reversalReview("rv-idem-app", "bob", "APPROVE", 1,
                        "2026-03-10T11:00:00Z"), 200);
        assertEquals(8000L, approved.get("remainingAvailableCents").asLong());
        JsonNode replayAfter = postJson("/api/batches/" + BATCH + "/reversals", submitBody, 200);
        assertEquals("PENDING", replayAfter.get("status").asText(),
                "重放不得因后续余额/状态变化重新计算");
        assertEquals(13000L, replayAfter.get("remainingAvailableCents").asLong());

        // 同键异参（仅精确时刻不同）→ 409
        postJson("/api/batches/" + BATCH + "/reversals",
                TestFixtures.reversalSubmit("rv-idem", DECL, "COR-IDEM",
                        "幂等申请", 5000L, "alice", 1, "2026-03-10T10:00:01Z"), 409);

        // 审批重放：同键同参返回首次 APPROVED 冻结结果，版本仍为 2
        String reviewBody = TestFixtures.reversalReview("rv-idem-app", "bob", "APPROVE", 1,
                "2026-03-10T11:00:00Z");
        JsonNode replayReview = postJson(
                "/api/batches/" + BATCH + "/reversals/" + reversalId + "/review", reviewBody, 200);
        assertEquals("APPROVED", replayReview.get("status").asText());
        assertEquals(2, replayReview.get("versionNo").asInt());

        // 失败不占键：超额申请失败后，同一 requestId 以合法参数重试成功
        Integer recordsBefore = jdbc.queryForObject(
                "SELECT COUNT(*) FROM t_idempotent_record WHERE request_id = 'rv-retry'",
                Integer.class);
        assertEquals(0, recordsBefore, "失败事务回滚后不得残留占坑行");
        postJson("/api/batches/" + BATCH + "/reversals",
                TestFixtures.reversalSubmit("rv-retry", DECL, "COR-RETRY",
                        "先超额", 999999L, "alice", 1, "2026-03-10T12:00:00Z"), 422);
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM t_idempotent_record WHERE request_id = 'rv-retry'",
                Integer.class), "失败不占键");
        JsonNode retry = postJson("/api/batches/" + BATCH + "/reversals",
                TestFixtures.reversalSubmit("rv-retry", DECL, "COR-RETRY",
                        "再合法", 1000L, "alice", 1, "2026-03-10T12:05:00Z"), 200);
        assertNotNull(retry.get("id"));
    }

    /** 结构化指纹：朴素拼接会碰撞的两组参数必须被区分（合法文本含分隔符）。 */
    @Test
    void structuredFingerprintDistinguishesSeparatorBearingTexts() throws Exception {
        closedSingleBatch(BATCH, DECL);

        // 朴素 '|' 拼接下两组参数相同：("x|y","z") 与 ("x","y|z")；结构化 JSON 必须区分。
        postJson("/api/batches/" + BATCH + "/reversals",
                TestFixtures.reversalSubmit("rv-fp", DECL, "x|y",
                        "z", 100L, "a", 1, "2026-03-10T10:00:00Z"), 200);
        postJson("/api/batches/" + BATCH + "/reversals",
                TestFixtures.reversalSubmit("rv-fp", "x", "y|z",
                        "z", 100L, "a", 1, "2026-03-10T10:00:00Z"), 409);
        // 同键同参（原因文本含引号、竖线与反斜杠）必须识别为同一请求
        String tricky = "原因|第二段, 含\"引号\"与\\反斜杠";
        String body = TestFixtures.reversalSubmit("rv-fp-2", DECL, "COR-T",
                tricky, 100L, "a", 1, "2026-03-10T10:02:00Z");
        JsonNode a = postJson("/api/batches/" + BATCH + "/reversals", body, 200);
        JsonNode b = postJson("/api/batches/" + BATCH + "/reversals", body, 200);
        assertEquals(a.get("id").asLong(), b.get("id").asLong());
        assertEquals(tricky, b.get("reason").asText());
    }

    /** 同流水异金额拒绝、同流水同金额去重；乱序到账不改变总额。 */
    @Test
    void receiptSerialRulesAndOutOfOrderArrival() throws Exception {
        closedSingleBatch(BATCH, DECL);
        long id = postJson("/api/batches/" + BATCH + "/reversals",
                TestFixtures.reversalSubmit("rv-oo", DECL, "COR-OO",
                        "乱序到账", 7000L, "alice", 1, "2026-03-10T10:00:00Z"), 200)
                .get("id").asLong();
        postJson("/api/batches/" + BATCH + "/reversals/" + id + "/review",
                TestFixtures.reversalReview("rv-oo-r", "bob", "APPROVE", 1,
                        "2026-03-10T11:00:00Z"), 200);

        // 先到 2000，再“乱序”到 1000，再到 4000
        postJson("/api/batches/" + BATCH + "/reversals/" + id + "/receipts",
                TestFixtures.clawbackReceipt("rc-2", "RC-S2", 2000L, 2,
                        "2026-03-12T10:00:00Z"), 200);
        postJson("/api/batches/" + BATCH + "/reversals/" + id + "/receipts",
                TestFixtures.clawbackReceipt("rc-1", "RC-S1", 1000L, 3,
                        "2026-03-11T10:00:00Z"), 200);

        // 同流水异金额 → 422
        JsonNode diff = postJson("/api/batches/" + BATCH + "/reversals/" + id + "/receipts",
                TestFixtures.clawbackReceipt("rc-1-x", "RC-S1", 1500L, 4,
                        "2026-03-12T11:00:00Z"), 422);
        assertTrue(diff.get("error").asText().contains("金额/归属不一致"));
        // 同流水同金额（重发/乱序重复投递）→ 去重成功，不新增金额
        JsonNode dedup = postJson("/api/batches/" + BATCH + "/reversals/" + id + "/receipts",
                TestFixtures.clawbackReceipt("rc-1-again", "RC-S1", 1000L, 4,
                        "2026-03-12T11:05:00Z"), 200);
        assertEquals(3000L, dedup.get("receivedCents").asLong());
        assertEquals(4, dedup.get("versionNo").asInt(), "去重不产生版本变更");

        postJson("/api/batches/" + BATCH + "/reversals/" + id + "/receipts",
                TestFixtures.clawbackReceipt("rc-3", "RC-S3", 4000L, 4,
                        "2026-03-13T10:00:00Z"), 200);
        JsonNode order = getJson("/api/batches/" + BATCH + "/reversals/" + id, 200);
        assertEquals("COMPLETED", order.get("status").asText());
        assertEquals(7000L, order.get("receivedCents").asLong());
        assertEquals(3, order.get("receipts").size(), "重复流水不得新增回执行");
    }
}
