package com.example.rebate.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.rebate.testsupport.TestFixtures;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

/**
 * 冲回失败分支：400 参数非法、404 对象不存在、422 业务拒绝；
 * 驳回不占额、未批准不能登记回执、同流水异金额拒绝、复核人不可同提交人、版本不符 409。
 */
class ReversalFailureTest extends ReversalApiTest {

    private void seed() throws Exception {
        seedClosedBatch("B-F", "CD-F", "1000", "20");
    }

    @Test
    void invalidParamsReturn400() throws Exception {
        seed();
        // 金额非正
        postJson(reversals("B-F"),
                TestFixtures.reversalSubmit("bad-amt", "CD-F", "C-BAD", "x", 0, "clerk-a"),
                400);
        // 缺字段（reason 为空）
        postJson(reversals("B-F"),
                """
                {"requestId":"bad-reason","declarationNo":"CD-F","correctionNo":"C-BAD2",
                 "reason":"","amountCents":100,"submittedBy":"clerk-a"}
                """, 400);
        // expectedVersion 非法（0）
        postJson(review("B-F", "C-BAD3"),
                TestFixtures.reversalReview("bad-ver", 0, "reviewer-b", true, null), 400);
        // JSON 语法错误 -> 400
        postJson(reversals("B-F"), "{not-json", 400);
    }

    @Test
    void malformedInstantReturns400() throws Exception {
        seed();
        postJson(reversals("B-F"),
                TestFixtures.reversalSubmit("mi-sub", "CD-F", "C-MI", "x", 100, "clerk-a"), 200);
        postJson(review("B-F", "C-MI"),
                TestFixtures.reversalReview("mi-rev", 1, "reviewer-b", true, null), 200);
        // occurredAt 不是合法时刻 -> 400
        postJson(receipts("B-F", "C-MI"),
                """
                {"requestId":"mi-rcp","expectedVersion":2,"receiptSerial":"R-MI",
                 "amountCents":100,"occurredAt":"not-a-timestamp"}
                """, 400);
    }

    @Test
    void missingObjectsReturn404() throws Exception {
        seed();
        // 批次不存在
        postJson(reversals("B-NOPE"),
                TestFixtures.reversalSubmit("m-batch", "CD-F", "C-X", "x", 100, "clerk-a"),
                404);
        // 报关单不属于该结案批次
        postJson(reversals("B-F"),
                TestFixtures.reversalSubmit("m-decl", "CD-OTHER", "C-X", "x", 100, "clerk-a"),
                404);
        // 冲回单不存在
        getJson(detail("B-F", "C-MISSING"), 404);
    }

    @Test
    void onlyClosedBatchAcceptsReversal() throws Exception {
        // 批次结案前（APPROVED 未退库）提交冲回 -> 422
        seedLibrary();
        postJson("/api/collect/customs",
                TestFixtures.customs("c-open", "CD-OPEN", TestFixtures.P1, "1000", "20"), 200);
        postJson("/api/collect/invoices",
                TestFixtures.invoice("i-open", "INV-OPEN", "CD-OPEN", TestFixtures.P1, "900"), 200);
        postJson("/api/batches/check",
                TestFixtures.check("chk-open", "B-OPEN", "2026-02-01", "CD-OPEN"), 200);
        postJson("/api/batches/B-OPEN/declare",
                TestFixtures.declare("decl-open", false), 200);
        postJson("/api/batches/B-OPEN/approve",
                TestFixtures.requestId("appr-open"), 200);
        postJson(reversals("B-OPEN"),
                TestFixtures.reversalSubmit("open-sub", "CD-OPEN", "C-O", "x", 100, "a"), 422);
    }

    @Test
    void rejectDoesNotOccupyAndCannotRegisterReceipt() throws Exception {
        seed();
        postJson(reversals("B-F"),
                TestFixtures.reversalSubmit("rj-sub", "CD-F", "C-RJ", "存疑", 5000, "clerk-a"),
                200);
        // 驳回必须填原因 -> 400
        postJson(review("B-F", "C-RJ"),
                TestFixtures.reversalReview("rj-no-reason", 1, "reviewer-b", false, null), 400);
        // 正常驳回
        JsonNode rejected = postJson(review("B-F", "C-RJ"),
                TestFixtures.reversalReview("rj-rev", 1, "reviewer-b", false, "资料不全"), 200);
        assertEquals("REJECTED", rejected.get("status").asText());
        assertEquals(2, rejected.get("version").asInt());
        assertEquals(0, rejected.get("occupiedCents").asLong());
        assertEquals(13000, rejected.get("remainingCents").asLong());

        // 已驳回不能登记回执 -> 422
        postJson(receipts("B-F", "C-RJ"),
                TestFixtures.reversalReceipt("rj-rcp", 2, "R-RJ", 100,
                        "2026-03-01T00:00:00Z"), 422);
        // 已驳回不能再次复核（版本也已变为 2）-> 409
        postJson(review("B-F", "C-RJ"),
                TestFixtures.reversalReview("rj-again", 2, "reviewer-b", true, null), 409);

        // 余额：无占用，无待复核
        JsonNode bal = getJson(balance("B-F", "CD-F"), 200);
        assertEquals(0, bal.get("occupiedCents").asLong());
        assertEquals(0, bal.get("pendingCents").asLong());
        assertEquals(13000, bal.get("remainingCents").asLong());
    }

    @Test
    void reviewerMustDifferFromSubmitter() throws Exception {
        seed();
        postJson(reversals("B-F"),
                TestFixtures.reversalSubmit("same-sub", "CD-F", "C-SAME", "x", 100, "clerk-a"),
                200);
        postJson(review("B-F", "C-SAME"),
                TestFixtures.reversalReview("same-rev", 1, "clerk-a", true, null), 422);
    }

    @Test
    void unapprovedAndOverLimitReceiptsRejected() throws Exception {
        seed();
        postJson(reversals("B-F"),
                TestFixtures.reversalSubmit("pend-sub", "CD-F", "C-PEND", "x", 5000, "clerk-a"),
                200);
        // 待复核单不能登记回执 -> 422
        postJson(receipts("B-F", "C-PEND"),
                TestFixtures.reversalReceipt("pend-rcp", 1, "R-PEND", 100,
                        "2026-03-01T00:00:00Z"), 422);

        postJson(review("B-F", "C-PEND"),
                TestFixtures.reversalReview("pend-rev", 1, "reviewer-b", true, null), 200);
        // 版本不符：批准后版本为 2，仍传 1 -> 409
        postJson(receipts("B-F", "C-PEND"),
                TestFixtures.reversalReceipt("pend-ver", 1, "R-VER", 100,
                        "2026-03-01T00:00:00Z"), 409);
        // 单笔超额 -> 422（批准 5000，登记 5001）
        postJson(receipts("B-F", "C-PEND"),
                TestFixtures.reversalReceipt("pend-over", 2, "R-OVER", 5001,
                        "2026-03-01T00:00:00Z"), 422);

        // 先到账 3000，再登记 3000 将累计 6000 > 5000 -> 422
        postJson(receipts("B-F", "C-PEND"),
                TestFixtures.reversalReceipt("pend-3000", 2, "R-3000", 3000,
                        "2026-03-01T00:00:00Z"), 200);
        postJson(receipts("B-F", "C-PEND"),
                TestFixtures.reversalReceipt("pend-3000b", 3, "R-3000B", 3000,
                        "2026-03-02T00:00:00Z"), 422);
    }

    @Test
    void sameSerialDifferentAmountRejectedSameAmountIdempotent() throws Exception {
        seed();
        postJson(reversals("B-F"),
                TestFixtures.reversalSubmit("ser-sub", "CD-F", "C-SER", "x", 5000, "clerk-a"),
                200);
        postJson(review("B-F", "C-SER"),
                TestFixtures.reversalReview("ser-rev", 1, "reviewer-b", true, null), 200);
        String receipt = TestFixtures.reversalReceipt("ser-rcp", 2, "R-SAME", 1000,
                "2026-03-01T00:00:00Z");
        JsonNode first = postJson(receipts("B-F", "C-SER"), receipt, 200);
        assertEquals(1000, first.get("collectedCents").asLong());
        // 同键同参重放：回放首次冻结结果，累计不变
        JsonNode replay = postJson(receipts("B-F", "C-SER"), receipt, 200);
        assertEquals(1000, replay.get("collectedCents").asLong());
        // 同流水异金额（换 requestId 与版本）-> 422
        JsonNode diff = postJson(receipts("B-F", "C-SER"),
                TestFixtures.reversalReceipt("ser-rcp-diff", 3, "R-SAME", 999,
                        "2026-03-01T00:00:00Z"), 422);
        assertTrue(diff.get("error").asText().contains("不一致"));
        // 仅一条回执明细
        JsonNode detailNode = getJson(detail("B-F", "C-SER"), 200);
        assertEquals(1, detailNode.get("receipts").size());
    }

    @Test
    void duplicateCorrectionNumberInSameBatchConflicts() throws Exception {
        seed();
        postJson(reversals("B-F"),
                TestFixtures.reversalSubmit("dup-1", "CD-F", "C-DUP", "x", 100, "clerk-a"),
                200);
        JsonNode dup = postJson(reversals("B-F"),
                TestFixtures.reversalSubmit("dup-2", "CD-F", "C-DUP", "y", 200, "clerk-a"),
                409);
        assertTrue(dup.get("error").asText().contains("C-DUP"));
    }

    @Test
    void reasonWithStructuralSeparatorsIsPreserved() throws Exception {
        seed();
        // 原因文本含指纹拼接分隔符 '|' 与 JSON 结构字符，须原样保存。
        String rawJson = """
                {"requestId":"sep-1","declarationNo":"CD-F","correctionNo":"C-SEP-1",
                 "reason":"a|b\\", \\"x\\":[1,2]|c","amountCents":100,"submittedBy":"clerk-a"}
                """;
        postJson(reversals("B-F"), rawJson, 200);
        JsonNode view = getJson(detail("B-F", "C-SEP-1"), 200);
        assertEquals("a|b\", \"x\":[1,2]|c", view.get("reason").asText());
    }
}
