package com.example.rebate.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import com.example.rebate.testsupport.BaseApiTest;
import com.example.rebate.testsupport.TestFixtures;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

/**
 * 新增冲回写操作的 requestId 幂等与结构化指纹：
 * 同键同参重放首次冻结结果、异参 409、失败不占键；业务、版本与幂等结果同事务。
 */
class ClawbackIdempotencyTest extends BaseApiTest {

    private static final String BATCH = "B-CB-IDEM";
    private static final String DECL = "CD-CI";

    /** CD-CI: 130 元 = 13000 分。 */
    private void prepare() throws Exception {
        seedLibrary();
        postJson("/api/collect/customs",
                TestFixtures.customs("ci-c", DECL, TestFixtures.P1, "1000", "20"), 200);
        postJson("/api/collect/invoices",
                TestFixtures.invoice("ci-i", "INV-CI", DECL, TestFixtures.P1, "900"), 200);
        postJson("/api/batches/check",
                TestFixtures.check("ci-chk", BATCH, "2026-02-01", DECL), 200);
        postJson("/api/batches/" + BATCH + "/declare",
                TestFixtures.declare("ci-decl", false), 200);
        postJson("/api/batches/" + BATCH + "/approve",
                TestFixtures.requestId("ci-appr"), 200);
        postJson("/api/batches/" + BATCH + "/treasury",
                TestFixtures.treasury("ci-w", "GK-CI", "130.00"), 200);
        postJson("/api/batches/" + BATCH + "/close",
                TestFixtures.requestId("ci-close"), 200);
    }

    private long submit(String reqId, String corrNo, long cents, String reason, String who)
            throws Exception {
        JsonNode node = postJson("/api/batches/" + BATCH + "/clawbacks",
                TestFixtures.clawbackSubmit(reqId, DECL, corrNo, reason, cents, who), 200);
        return node.get("clawback").get("id").asLong();
    }

    @Test
    void sameKeySameParamsReplaysFirstSubmitAndCreatesOnce() throws Exception {
        prepare();
        String body = TestFixtures.clawbackSubmit("idem-sub", DECL, "CORR-I1",
                "原因A", 5000L, "alice");
        JsonNode first = postJson("/api/batches/" + BATCH + "/clawbacks", body, 200);
        JsonNode replay = postJson("/api/batches/" + BATCH + "/clawbacks", body, 200);
        assertEquals(first.get("clawback").get("id").asLong(),
                replay.get("clawback").get("id").asLong());
        assertEquals(first.get("clawback").get("externalCorrectionNo").asText(),
                replay.get("clawback").get("externalCorrectionNo").asText());

        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM t_clawback WHERE external_correction_no = 'CORR-I1'",
                Integer.class);
        assertEquals(1, count, "同键重放不得重复建单");
    }

    @Test
    void sameKeyDifferentParamsConflicts() throws Exception {
        prepare();
        postJson("/api/batches/" + BATCH + "/clawbacks",
                TestFixtures.clawbackSubmit("idem-key", DECL, "CORR-A",
                        "原因", 1000L, "alice"), 200);
        // 同 requestId 仅金额不同 -> 409，且不新增单
        postJson("/api/batches/" + BATCH + "/clawbacks",
                TestFixtures.clawbackSubmit("idem-key", DECL, "CORR-B",
                        "原因", 2000L, "alice"), 409);
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM t_clawback", Integer.class);
        assertEquals(1, count);
    }

    @Test
    void failureDoesNotHoldKey() throws Exception {
        prepare();
        // 首次用不存在的报关单，业务 404，占坑随事务回滚
        postJson("/api/batches/" + BATCH + "/clawbacks",
                TestFixtures.clawbackSubmit("idem-retry", "CD-GHOST", "CORR-G",
                        "原因", 1000L, "alice"), 404);
        // 同一 requestId 改成合法报关单重试应成功（若占坑未释放会因异参 409）
        JsonNode ok = postJson("/api/batches/" + BATCH + "/clawbacks",
                TestFixtures.clawbackSubmit("idem-retry", DECL, "CORR-G",
                        "原因", 1000L, "alice"), 200);
        assertEquals("PENDING", ok.get("clawback").get("status").asText());
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM t_clawback WHERE external_correction_no = 'CORR-G'",
                Integer.class);
        assertEquals(1, count);
    }

    @Test
    void approvedReviewReplayIsFrozenAndNotRecomputed() throws Exception {
        prepare();
        long id1 = submit("s1", "CORR-F1", 5000L, "原因", "alice");
        long id2 = submit("s2", "CORR-F2", 8000L, "原因", "alice");

        String reviewBody = TestFixtures.clawbackReview("rev-frozen", 1, true, "bob");
        JsonNode first = postJson(
                "/api/batches/" + BATCH + "/clawbacks/" + id1 + "/review", reviewBody, 200);
        assertEquals(5000L, first.get("occupiedCents").asLong());
        assertEquals(8000L, first.get("remainingCents").asLong());

        // 批准第二单 8000，把额度占满到 13000
        JsonNode second = postJson(
                "/api/batches/" + BATCH + "/clawbacks/" + id2 + "/review",
                TestFixtures.clawbackReview("rev-second", 1, true, "carol"), 200);
        assertEquals(13000L, second.get("occupiedCents").asLong());
        assertEquals(0L, second.get("remainingCents").asLong());

        // 重放第一笔审批：必须返回首次冻结快照（占用 5000、剩余 8000），不随后续余额重算
        JsonNode replay = postJson(
                "/api/batches/" + BATCH + "/clawbacks/" + id1 + "/review", reviewBody, 200);
        assertEquals(5000L, replay.get("occupiedCents").asLong());
        assertEquals(8000L, replay.get("remainingCents").asLong());
        assertEquals(2, replay.get("clawback").get("version").asInt());
    }

    @Test
    void structuredFingerprintDistinguishesDelimiterText() throws Exception {
        prepare();
        // 合法原因含分隔符 | 与引号，结构化 JSON 指纹仍可区分不同字段/取值
        long idA = submit("fp-a", "CORR-D1", 1000L,
                "品名|分开\"嵌套", "alice");
        // 同键但原因里分隔符位置/内容不同 -> 409
        postJson("/api/batches/" + BATCH + "/clawbacks",
                TestFixtures.clawbackSubmit("fp-a", DECL, "CORR-D1",
                        "品名|分|开\"嵌套", 1000L, "alice"), 409);

        // 不同 requestId、仅分隔符切分位置不同的两条原因都能原样落库
        long idB = submit("fp-b", "CORR-D2", 1000L,
                "品名分|开\"嵌套", "alice");
        assertNotEquals(idA, idB);
        JsonNode detail = getJson(
                "/api/batches/" + BATCH + "/clawbacks/" + idA, 200);
        assertEquals("品名|分开\"嵌套", detail.get("clawback").get("reason").asText());
    }

    @Test
    void receiptSameKeySameParamsReplaysWithoutDoubleCount() throws Exception {
        prepare();
        long id = submit("rc-s", "CORR-RC", 1000L, "原因", "alice");
        postJson("/api/batches/" + BATCH + "/clawbacks/" + id + "/review",
                TestFixtures.clawbackReview("rc-rv", 1, true, "bob"), 200);

        String body = TestFixtures.clawbackReceipt("rc-key", "RCS-1", 400L,
                "2026-03-01T10:00:00Z", 2);
        JsonNode first = postJson(
                "/api/batches/" + BATCH + "/clawbacks/" + id + "/receipts", body, 200);
        JsonNode replay = postJson(
                "/api/batches/" + BATCH + "/clawbacks/" + id + "/receipts", body, 200);
        assertEquals(400L, first.get("receivedAmountCents").asLong());
        assertEquals(400L, replay.get("receivedAmountCents").asLong());

        Integer receipts = jdbc.queryForObject(
                "SELECT COUNT(*) FROM t_clawback_receipt WHERE serial_no = 'RCS-1'",
                Integer.class);
        assertEquals(1, receipts, "同键重放不得重复登记回执");
    }
}
