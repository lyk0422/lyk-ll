package com.example.rebate.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.rebate.testsupport.TestFixtures;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

/**
 * 新增冲回写操作的同事务幂等：
 * 成功重放返回首次冻结结果（不随后续余额变化重算）；同键异参 409；失败不占键、不产生业务数据。
 */
class ReversalIdempotencyTest extends ReversalApiTest {

    @Test
    void submitAndReviewReplayFirstFrozenResult() throws Exception {
        seedClosedBatch("B-IDEM", "CD-IDEM", "1000", "20");

        String submitBody = TestFixtures.reversalSubmit("idem-sub", "CD-IDEM", "CORR-I",
                "原因", 5000, "clerk-a");
        JsonNode first = postJson(reversals("B-IDEM"), submitBody, 200);
        JsonNode replay = postJson(reversals("B-IDEM"), submitBody, 200);
        assertEquals(first.get("id").asLong(), replay.get("id").asLong());
        assertEquals(first.get("version").asInt(), replay.get("version").asInt());
        Integer reversalRows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM t_reversal WHERE correction_no = 'CORR-I'", Integer.class);
        assertEquals(1, reversalRows, "同键重放不得重复建单");

        String reviewBody = TestFixtures.reversalReview("idem-rev", 1, "reviewer-b", true, null);
        JsonNode approvedFirst = postJson(review("B-IDEM", "CORR-I"), reviewBody, 200);
        assertEquals(2, approvedFirst.get("version").asInt());

        // 再批准第二张冲回单改变余额后，重放第一张批准的 requestId 仍返回首次冻结结果（占用 5000）。
        postJson(reversals("B-IDEM"),
                TestFixtures.reversalSubmit("idem-sub-2", "CD-IDEM", "CORR-I2",
                        "原因2", 2000, "clerk-a"), 200);
        postJson(review("B-IDEM", "CORR-I2"),
                TestFixtures.reversalReview("idem-rev-2", 1, "reviewer-c", true, null), 200);

        JsonNode approvedReplay = postJson(review("B-IDEM", "CORR-I"), reviewBody, 200);
        assertEquals(approvedFirst.get("version").asInt(), approvedReplay.get("version").asInt());
        assertEquals(5000, approvedReplay.get("occupiedCents").asLong(),
                "重放必须返回首次冻结时的余额，不得按后续余额重新计算");
    }

    @Test
    void sameKeyDifferentParamsConflictsAndStateUnchanged() throws Exception {
        seedClosedBatch("B-C", "CD-C", "1000", "20");
        // 首次提交金额 1000
        postJson(reversals("B-C"),
                TestFixtures.reversalSubmit("k-sub", "CD-C", "CORR-C1", "x", 1000, "clerk-a"),
                200);
        // 同 requestId 改成金额 2000 -> 409
        JsonNode conflict = postJson(reversals("B-C"),
                TestFixtures.reversalSubmit("k-sub", "CD-C", "CORR-C2", "x", 2000, "clerk-a"),
                409);
        assertTrue(conflict.get("error").asText().contains("不同业务参数"));
        // 仅存在第一张单
        Integer rows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM t_reversal WHERE batch_id = "
                        + "(SELECT id FROM t_declaration_batch WHERE batch_no = 'B-C')",
                Integer.class);
        assertEquals(1, rows);

        // 回执：同 requestId 仅 expectedVersion 不同 -> 409（版本纳入指纹）
        postJson(review("B-C", "CORR-C1"),
                TestFixtures.reversalReview("k-rev", 1, "reviewer-b", true, null), 200);
        postJson(receipts("B-C", "CORR-C1"),
                TestFixtures.reversalReceipt("k-rcp", 2, "RCV-C", 100,
                        "2026-03-01T00:00:00Z"), 200);
        postJson(receipts("B-C", "CORR-C1"),
                TestFixtures.reversalReceipt("k-rcp", 3, "RCV-C", 100,
                        "2026-03-01T00:00:00Z"), 409);
        // 同 requestId 仅精确时刻不同 -> 409（时刻纳入指纹）
        postJson(receipts("B-C", "CORR-C1"),
                TestFixtures.reversalReceipt("k-rcp2", 3, "RCV-C2", 100,
                        "2026-03-02T00:00:00Z"), 200);
        postJson(receipts("B-C", "CORR-C1"),
                TestFixtures.reversalReceipt("k-rcp2", 3, "RCV-C2", 100,
                        "2026-03-02T01:00:00Z"), 409);
    }

    @Test
    void failureDoesNotHoldKeyAndLeavesNoData() throws Exception {
        seedClosedBatch("B-R", "CD-R", "1000", "20");
        // 首次失败：金额超过剩余可冲额 -> 422
        JsonNode failed = postJson(reversals("B-R"),
                TestFixtures.reversalSubmit("retry-key", "CD-R", "CORR-R", "x", 99999, "clerk-a"),
                422);
        assertTrue(failed.get("error").asText().contains("剩余可冲额"));
        // 失败不占键：同键换成合法参数应成功
        JsonNode ok = postJson(reversals("B-R"),
                TestFixtures.reversalSubmit("retry-key", "CD-R", "CORR-R", "x", 100, "clerk-a"),
                200);
        assertEquals("PENDING", ok.get("status").asText());
        // 幂等记录最终仅 1 行（失败行已随事务回滚）
        Integer idemRows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM t_idempotent_record WHERE request_id = 'retry-key'",
                Integer.class);
        assertEquals(1, idemRows);
        Integer reversalRows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM t_reversal WHERE correction_no = 'CORR-R'", Integer.class);
        assertEquals(1, reversalRows);
    }

    @Test
    void failedReviewReleasesKeyAndCanBeRetriedWithSameKey() throws Exception {
        seedClosedBatch("B-RR", "CD-RR", "1000", "20");
        postJson(reversals("B-RR"),
                TestFixtures.reversalSubmit("rr-sub", "CD-RR", "CORR-RR", "x", 100, "clerk-a"),
                200);
        // 复核人同提交人 -> 422，键不保留
        postJson(review("B-RR", "CORR-RR"),
                TestFixtures.reversalReview("rr-key", 1, "clerk-a", true, null), 422);
        // 同键换不同复核人 -> 成功
        JsonNode approved = postJson(review("B-RR", "CORR-RR"),
                TestFixtures.reversalReview("rr-key", 1, "reviewer-b", true, null), 200);
        assertEquals("APPROVED", approved.get("status").asText());
    }
}
