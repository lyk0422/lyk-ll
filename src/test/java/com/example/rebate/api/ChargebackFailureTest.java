package com.example.rebate.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.rebate.testsupport.BaseApiTest;
import com.example.rebate.testsupport.TestFixtures;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

/**
 * 冲回失败分支与 requestId 幂等边界：
 * 参数非法 400、对象不存在 404、业务拒绝 422、指纹冲突 409；
 * 同键同参回放首次冻结结果；失败回滚不占键；成功审批重放不因余额变化重算。
 */
class ChargebackFailureTest extends BaseApiTest {

    private String prepareClosed(String batchNo, String declNo, String prefix) throws Exception {
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

    private String prepareApprovedNotClosed(String batchNo, String declNo, String prefix)
            throws Exception {
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
        return batchNo;
    }

    @Test
    void invalidParamsReturn400() throws Exception {
        prepareClosed("B-F1", "CD-F1", "f1");
        // 金额非正数
        postJson("/api/batches/B-F1/chargebacks",
                TestFixtures.chargebackSubmit("f1-zero", "CD-F1", "C-Z", "x", 0L, "a"), 400);
        postJson("/api/batches/B-F1/chargebacks",
                TestFixtures.chargebackSubmit("f1-neg", "CD-F1", "C-N", "x", -5L, "a"), 400);
        // 空原因 / 空提交人
        postJson("/api/batches/B-F1/chargebacks",
                TestFixtures.chargebackSubmit("f1-blank", "CD-F1", "C-B", "  ", 100L, "a"), 400);
        // 复核版本非正数
        postJson("/api/batches/B-F1/chargebacks/C-Z/review",
                TestFixtures.chargebackReview("f1-v0", 0, "APPROVE", "bob"), 400);

        // 先建一张合法待复核单，用于复核动作与回执校验
        postJson("/api/batches/B-F1/chargebacks",
                TestFixtures.chargebackSubmit("f1-ok", "CD-F1", "C-OK", "x", 100L, "a"), 200);
        // 复核动作取值非法 → 400
        postJson("/api/batches/B-F1/chargebacks/C-OK/review",
                TestFixtures.chargebackReview("f1-bad-action", 1, "MAYBE", "bob"), 400);

        // 回执金额非正数
        String badAmount = """
                {"requestId":"f1-bad-amt","expectedVersion":1,"receiptSerial":"R-AMT",
                 "receivedCents":0,"receivedAt":"2026-03-01T10:00:00Z"}
                """;
        postJson("/api/batches/B-F1/chargebacks/C-OK/receipts", badAmount, 400);
        // 回执时刻非法
        String badInstant = """
                {"requestId":"f1-bad-time","expectedVersion":1,"receiptSerial":"R-BAD",
                 "receivedCents":100,"receivedAt":"not-a-time"}
                """;
        postJson("/api/batches/B-F1/chargebacks/C-OK/receipts", badInstant, 400);
    }

    @Test
    void missingObjectsReturn404() throws Exception {
        prepareClosed("B-F2", "CD-F2", "f2");
        // 批次不存在
        postJson("/api/batches/NOPE/chargebacks",
                TestFixtures.chargebackSubmit("f2-no-batch", "CD-F2", "C1", "x", 100L, "a"), 404);
        // 报关单不在批次内
        postJson("/api/batches/B-F2/chargebacks",
                TestFixtures.chargebackSubmit("f2-no-decl", "CD-OTHER", "C1", "x", 100L, "a"), 404);
        // 冲回单不存在
        getJson("/api/batches/B-F2/chargebacks/NO-COR", 404);
        postJson("/api/batches/B-F2/chargebacks/NO-COR/review",
                TestFixtures.chargebackReview("f2-no-order", 1, "APPROVE", "bob"), 404);
    }

    @Test
    void businessRejectionsReturn422() throws Exception {
        String b = prepareClosed("B-F3", "CD-F3", "f3");

        // 未结案批次不能冲回（准备一个仅审核通过、未退库结案的批次）
        prepareApprovedNotClosed("B-F3-OPEN", "CD-F3O", "f3o");
        postJson("/api/batches/B-F3-OPEN/chargebacks",
                TestFixtures.chargebackSubmit("f3-open", "CD-F3O", "C1", "x", 100L, "a"), 422);

        // 提交超剩余可冲额：冻结 13000
        postJson("/api/batches/B-F3/chargebacks",
                TestFixtures.chargebackSubmit("f3-big", "CD-F3", "C-BIG", "x", 13001L, "a"), 422);

        // 合法提交 8000 并批准，剩 5000
        postJson("/api/batches/B-F3/chargebacks",
                TestFixtures.chargebackSubmit("f3-s", "CD-F3", "C-OK", "x", 8000L, "a"), 200);
        postJson("/api/batches/B-F3/chargebacks/C-OK/review",
                TestFixtures.chargebackReview("f3-r", 1, "APPROVE", "bob"), 200);

        // 提交第二笔 6000 > 剩余 5000：422 且不产生冲回单
        postJson("/api/batches/B-F3/chargebacks",
                TestFixtures.chargebackSubmit("f3-over", "CD-F3", "C-OVER", "x", 6000L, "a"), 422);

        // 两张待复核单各 3000，提交时都不超剩余 5000，但合计 6000 > 5000
        postJson("/api/batches/B-F3/chargebacks",
                TestFixtures.chargebackSubmit("f3-p1", "CD-F3", "C-P1", "x", 3000L, "a"), 200);
        postJson("/api/batches/B-F3/chargebacks",
                TestFixtures.chargebackSubmit("f3-p2", "CD-F3", "C-P2", "x", 3000L, "a"), 200);

        // 待复核单不能登记回执
        postJson("/api/batches/B-F3/chargebacks/C-P1/receipts",
                TestFixtures.receipt("f3-pr", 1, "RP-1", 100L, "2026-03-01T10:00:00Z"), 422);

        // 先批准 C-P1 占用 3000（累计 11000，剩 2000）
        postJson("/api/batches/B-F3/chargebacks/C-P1/review",
                TestFixtures.chargebackReview("f3-r1", 1, "APPROVE", "bob"), 200);
        // 再批准 C-P2：3000 > 剩余 2000，事务回滚 422，不占额、不产生追缴明细
        postJson("/api/batches/B-F3/chargebacks/C-P2/review",
                TestFixtures.chargebackReview("f3-r2", 1, "APPROVE", "bob"), 422);
        JsonNode balances = getJson("/api/batches/B-F3/chargebacks", 200);
        assertEquals(11000L, balances.get(0).get("occupiedCents").asLong(), "失败批准不得占额");
        assertEquals(2000L, balances.get(0).get("remainingCents").asLong());
        JsonNode p2 = getJson("/api/batches/B-F3/chargebacks/C-P2", 200);
        assertEquals("PENDING", p2.get("status").asText(), "失败回滚后冲回单仍待复核");
        assertTrue(p2.get("clawback").isNull());

        // 到账超批准金额：C-P1 批准 3000，先到 2000，再来 2000 → 422
        postJson("/api/batches/B-F3/chargebacks/C-P1/receipts",
                TestFixtures.receipt("f3-rc1", 1, "RP-2", 2000L, "2026-03-01T10:00:00Z"), 200);
        postJson("/api/batches/B-F3/chargebacks/C-P1/receipts",
                TestFixtures.receipt("f3-rc2", 2, "RP-3", 2000L, "2026-03-02T10:00:00Z"), 422);

        // 同流水异金额 409
        postJson("/api/batches/B-F3/chargebacks/C-P1/receipts",
                TestFixtures.receipt("f3-rc3", 2, "RP-2", 999L, "2026-03-03T10:00:00Z"), 409);
    }

    @Test
    void sameKeySameParamsReplayAndDifferentParamsConflict() throws Exception {
        String b = prepareClosed("B-F4", "CD-F4", "f4");
        String body = TestFixtures.chargebackSubmit("f4-key", "CD-F4", "C-1", "原因", 1000L, "a");
        JsonNode first = postJson("/api/batches/B-F4/chargebacks", body, 200);
        JsonNode replay = postJson("/api/batches/B-F4/chargebacks", body, 200);
        assertEquals(first.get("id").asLong(), replay.get("id").asLong());
        assertEquals(1, replay.get("version").asInt());

        // 同键异参（金额不同）→ 409，且不新增冲回单
        String changed = TestFixtures.chargebackSubmit("f4-key", "CD-F4", "C-1", "原因", 2000L, "a");
        postJson("/api/batches/B-F4/chargebacks", changed, 409);

        Integer orders = jdbc.queryForObject(
                "SELECT COUNT(*) FROM t_chargeback_order WHERE batch_id = "
                        + "(SELECT id FROM t_declaration_batch WHERE batch_no = 'B-F4')",
                Integer.class);
        assertEquals(1, orders, "异参 409 不得落库");
    }

    @Test
    void failedBusinessTxReleasesRequestId() throws Exception {
        String b = prepareClosed("B-F5", "CD-F5", "f5");
        // 首次失败（超余额 422）
        postJson("/api/batches/B-F5/chargebacks",
                TestFixtures.chargebackSubmit("f5-reuse", "CD-F5", "C-X", "x", 99999L, "a"), 422);
        // 同 requestId 用于合法参数应成功：证明失败回滚未占键
        JsonNode ok = postJson("/api/batches/B-F5/chargebacks",
                TestFixtures.chargebackSubmit("f5-reuse", "CD-F5", "C-Y", "x", 100L, "a"), 200);
        assertEquals("PENDING", ok.get("status").asText());
        assertEquals("C-Y", ok.get("correctionNo").asText());
    }

    @Test
    void expectedVersionMismatchConflictsAndSuccessfulApprovalReplayDoesNotReoccupy()
            throws Exception {
        String b = prepareClosed("B-F6", "CD-F6", "f6");
        postJson("/api/batches/B-F6/chargebacks",
                TestFixtures.chargebackSubmit("f6-s1", "CD-F6", "C-A", "x", 7000L, "a"), 200);
        postJson("/api/batches/B-F6/chargebacks",
                TestFixtures.chargebackSubmit("f6-s2", "CD-F6", "C-B", "x", 6000L, "a"), 200);

        String approveA = TestFixtures.chargebackReview("f6-appr-a", 1, "APPROVE", "bob");
        JsonNode approvedA = postJson("/api/batches/B-F6/chargebacks/C-A/review", approveA, 200);
        assertEquals(2, approvedA.get("version").asInt());
        JsonNode approvedB = postJson("/api/batches/B-F6/chargebacks/C-B/review",
                TestFixtures.chargebackReview("f6-appr-b", 1, "APPROVE", "bob"), 200);
        // B 6000 恰好把余额冲平（7000 + 6000 = 13000）
        assertEquals(0L, approvedB.get("remainingCents").asLong());

        // 用过期 expectedVersion 再批 A：状态已非 PENDING → 409
        postJson("/api/batches/B-F6/chargebacks/C-A/review",
                TestFixtures.chargebackReview("f6-stale", 1, "APPROVE", "bob"), 409);

        // 重放 A 的首次成功审批（同键同参）：回放首次冻结结果，不再次占用余额
        JsonNode replayA = postJson("/api/batches/B-F6/chargebacks/C-A/review", approveA, 200);
        assertEquals("APPROVED", replayA.get("status").asText());
        assertEquals(7000L, replayA.get("amountCents").asLong());
        JsonNode balances = getJson("/api/batches/B-F6/chargebacks", 200);
        assertEquals(13000L, balances.get(0).get("occupiedCents").asLong(),
                "重放不得重复占用余额");
    }

    @Test
    void duplicateCorrectionNumberConflicts() throws Exception {
        String b = prepareClosed("B-F7", "CD-F7", "f7");
        postJson("/api/batches/B-F7/chargebacks",
                TestFixtures.chargebackSubmit("f7-s1", "CD-F7", "DUP", "x", 100L, "a"), 200);
        postJson("/api/batches/B-F7/chargebacks",
                TestFixtures.chargebackSubmit("f7-s2", "CD-F7", "DUP", "y", 200L, "a"), 409);
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM t_chargeback_order WHERE correction_no = 'DUP'",
                Integer.class);
        assertEquals(1, count);
        assertTrue(true);
    }
}
