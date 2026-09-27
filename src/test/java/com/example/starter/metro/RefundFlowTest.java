package com.example.starter.metro;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 封账后退款冲正主流程与失败分支测试（H2 内存库，MySQL 兼容模式）。
 * 基线行程 A->F：16 公里 500 分，原清分份额 L1=188、L2=312，批次已封账。
 */
@SpringBootTest
@AutoConfigureMockMvc
class RefundFlowTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @BeforeEach
    void cleanDatabase() {
        jdbcTemplate.update("DELETE FROM adjustment_detail");
        jdbcTemplate.update("DELETE FROM adjustment_order");
        jdbcTemplate.update("DELETE FROM adjustment_batch");
        jdbcTemplate.update("DELETE FROM refund_order");
        jdbcTemplate.update("DELETE FROM clearing_detail");
        jdbcTemplate.update("DELETE FROM clearing_discrepancy");
        jdbcTemplate.update("DELETE FROM metro_transaction");
        jdbcTemplate.update("DELETE FROM clearing_batch");
        jdbcTemplate.update("DELETE FROM idempotency_record");
    }

    private JsonNode call(String url, Map<String, Object> payload, int expectedStatus) throws Exception {
        MvcResult result = mockMvc.perform(post(url)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(payload)))
                .andExpect(status().is(expectedStatus)).andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private Map<String, Object> body(Object... kv) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            map.put((String) kv[i], kv[i + 1]);
        }
        return map;
    }

    private JsonNode entry(String requestId, String txnNo, String cardId, String station,
                           String entryTime) throws Exception {
        return call("/api/metro/entry",
                body("requestId", requestId, "txnNo", txnNo, "cardId", cardId, "station", station,
                        "entryTime", entryTime), 200);
    }

    private JsonNode exit(String requestId, String txnNo, String station, String exitTime) throws Exception {
        return call("/api/metro/exit",
                body("requestId", requestId, "txnNo", txnNo, "station", station, "exitTime", exitTime),
                200);
    }

    private JsonNode estimate(String requestId, String txnNo) throws Exception {
        return call("/api/metro/estimate", body("requestId", requestId, "txnNo", txnNo), 200);
    }

    private void sealClearing(String requestId, String bizDate) throws Exception {
        call("/api/metro/clearing/seal", body("requestId", requestId, "bizDate", bizDate), 200);
    }

    private JsonNode refundApply(String requestId, String txnNo, long amount, String reason,
                                 String applicant, int expectedStatus) throws Exception {
        return call("/api/metro/refunds/apply",
                body("requestId", requestId, "txnNo", txnNo, "amountCents", amount, "reason", reason,
                        "applicant", applicant), expectedStatus);
    }

    private JsonNode refundConfirm(String requestId, String refundNo, String r1, String r2,
                                   int expectedVersion, String confirmTime, int expectedStatus)
            throws Exception {
        return call("/api/metro/refunds/confirm",
                body("requestId", requestId, "refundNo", refundNo, "reviewer1", r1, "reviewer2", r2,
                        "expectedVersion", expectedVersion, "confirmTime", confirmTime), expectedStatus);
    }

    private JsonNode adjustSeal(String requestId, int expectedVersion, String sealTime,
                                int expectedStatus) throws Exception {
        return call("/api/metro/refunds/adjustments/seal",
                body("requestId", requestId, "expectedVersion", expectedVersion, "sealTime", sealTime),
                expectedStatus);
    }

    private JsonNode getBalance(String txnNo) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/metro/refunds/balance/" + txnNo))
                .andExpect(status().isOk()).andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private JsonNode getBatch(String bizDate) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/metro/clearing/batches/" + bizDate))
                .andExpect(status().isOk()).andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    /** 准备已封账按实结算行程 T1（A->F，500 分，L1=188/L2=312）。 */
    private void prepareSealedTrip() throws Exception {
        entry("e1", "T1", "CARD1", "A", "2026-09-25T09:00:00");
        JsonNode exited = exit("x1", "T1", "F", "2026-09-25T09:40:00");
        assertEquals(500L, exited.get("fareCents").asLong());
        sealClearing("s1", "2026-09-25");
    }

    @Test
    void applyConfirmSealProducesIndependentNegativeAdjustmentsWithoutRewritingOriginal()
            throws Exception {
        prepareSealedTrip();

        JsonNode applied = refundApply("ra1", "T1", 100, "晚点致歉补偿", "alice", 200);
        assertEquals("PENDING", applied.get("status").asText());
        assertEquals(1, applied.get("version").asInt());
        String refundNo = applied.get("refundNo").asText();

        // 申请阶段不占用余额、不产生调整
        Long orderCountBefore = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM adjustment_order", Long.class);
        assertEquals(0L, orderCountBefore);

        JsonNode confirmed = refundConfirm("rc1", refundNo, "bob", "carol", 1,
                "2026-09-26T10:00:00", 200);
        assertEquals("CONFIRMED", confirmed.get("status").asText());
        assertEquals(2, confirmed.get("version").asInt());
        long batchId = confirmed.get("batchId").asLong();
        // 100 分按 188:312 分摊：floor 37+62=99，余 1 归份额更大的 L2
        JsonNode allocations = confirmed.get("allocations");
        long allocSum = 0;
        for (JsonNode a : allocations) {
            allocSum += a.get("amountCents").asLong();
            if ("L1".equals(a.get("lineCode").asText())) {
                assertEquals(-37L, a.get("amountCents").asLong());
            } else {
                assertEquals("L2", a.get("lineCode").asText());
                assertEquals(-63L, a.get("amountCents").asLong());
            }
        }
        assertEquals(-100L, allocSum);

        // 原流水与封账合计永不改写
        JsonNode originalBatch = getBatch("2026-09-25");
        assertEquals(500L, originalBatch.get("totalCents").asLong());
        assertEquals(1, originalBatch.get("txnCount").asInt());
        Long originalDetailSum = jdbcTemplate.queryForObject(
                "SELECT COALESCE(SUM(amount_cents),0) FROM clearing_detail", Long.class);
        assertEquals(500L, originalDetailSum);

        // 逐线路余额
        JsonNode balance = getBalance("T1");
        assertEquals(500L, balance.get("originalTotalCents").asLong());
        assertEquals(100L, balance.get("refundedCents").asLong());
        assertEquals(400L, balance.get("remainingCents").asLong());

        // 封存调账批次：快照 -100，封存后只读
        JsonNode sealed = adjustSeal("as1", 1, "2026-09-26T18:00:00", 200);
        assertEquals("SEALED", sealed.get("status").asText());
        assertEquals(-100L, sealed.get("totalCents").asLong());
        assertEquals(1, sealed.get("orderCount").asInt());
        assertEquals(2, sealed.get("version").asInt());
        assertEquals(batchId, sealed.get("batchId").asLong());

        // 封存后原批次仍无变化；成功退款的历史重放不受任何影响（无票卡实体可关闭，重放冻结结果）
        JsonNode replay = refundConfirm("rc1", refundNo, "bob", "carol", 1,
                "2026-09-26T10:00:00", 200);
        assertTrue(replay.get("replayed").asBoolean());
        assertEquals(-100L, replay.get("allocations").get(0).get("amountCents").asLong()
                + replay.get("allocations").get(1).get("amountCents").asLong());
        Long stillOne = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM adjustment_order", Long.class);
        assertEquals(1L, stillOne);
    }

    @Test
    void exactFullRefundLeavesZeroRemaining() throws Exception {
        prepareSealedTrip();
        JsonNode applied = refundApply("ra2", "T1", 500, "x", "alice", 200);
        JsonNode confirmed = refundConfirm("rc2", applied.get("refundNo").asText(), "bob", "carol",
                1, "2026-09-26T10:00:00", 200);
        long sum = 0;
        for (JsonNode a : confirmed.get("allocations")) {
            sum -= a.get("amountCents").asLong();
            if ("L1".equals(a.get("lineCode").asText())) {
                assertEquals(-188L, a.get("amountCents").asLong());
            } else {
                assertEquals(-312L, a.get("amountCents").asLong());
            }
        }
        assertEquals(500L, sum);
        JsonNode balance = getBalance("T1");
        assertEquals(0L, balance.get("remainingCents").asLong());
        assertEquals(500L, balance.get("refundedCents").asLong());
    }

    @Test
    void cumulativeRefundMustNotExceedOriginalFare() throws Exception {
        prepareSealedTrip();
        JsonNode first = refundApply("ra3a", "T1", 400, "x", "alice", 200);
        refundConfirm("rc3a", first.get("refundNo").asText(), "bob", "carol", 1,
                "2026-09-26T10:00:00", 200);

        // 累计 400+101 > 500：422，且不生成退款单
        JsonNode rejected = refundApply("ra3b", "T1", 101, "x", "alice", 422);
        assertEquals("REFUND_EXCEEDS_FARE", rejected.get("code").asText());
        Long pendingCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM refund_order WHERE amount_cents = 101", Long.class);
        assertEquals(0L, pendingCount);

        // 恰退满合法：400+100=500
        JsonNode second = refundApply("ra3c", "T1", 100, "x", "alice", 200);
        JsonNode confirmed = refundConfirm("rc3c", second.get("refundNo").asText(), "dave", "erin",
                1, "2026-09-26T11:00:00", 200);
        long total = 0;
        for (JsonNode a : confirmed.get("allocations")) {
            total -= a.get("amountCents").asLong();
        }
        assertEquals(100L, total);
        assertEquals(0L, getBalance("T1").get("remainingCents").asLong());

        // 余额已耗尽，再有退款单确认时被拒（422）
        JsonNode third = refundApply("ra3d", "T1", 1, "x", "alice", 422);
        assertEquals("REFUND_EXCEEDS_FARE", third.get("code").asText());
    }

    @Test
    void refundsBeforeSealOnUnsealedBatchAreRejected() throws Exception {
        entry("e4", "T4", "CARD4", "A", "2026-09-26T09:00:00");
        exit("x4", "T4", "F", "2026-09-26T09:40:00");
        // 业务日期 2026-09-26 批次未封账
        JsonNode error = refundApply("ra4", "T4", 10, "x", "alice", 422);
        assertEquals("BATCH_NOT_SEALED", error.get("code").asText());
    }

    @Test
    void enteredAndEstimatedTripsCannotBeRefunded() throws Exception {
        prepareSealedTrip();
        // 未出闸
        entry("e5", "T5", "CARD5", "A", "2026-09-25T08:00:00");
        JsonNode enteredError = refundApply("ra5a", "T5", 10, "x", "alice", 422);
        assertEquals("TRIP_NOT_SETTLED", enteredError.get("code").asText());

        // 暂记
        entry("e6", "T6", "CARD6", "A", "2026-09-25T08:00:00");
        estimate("m6", "T6");
        JsonNode estimatedError = refundApply("ra6", "T6", 10, "x", "alice", 422);
        assertEquals("TRIP_NOT_SETTLED", estimatedError.get("code").asText());
    }

    @Test
    void lateSettledTripWithoutClearingRecordCannotBeRefunded() throws Exception {
        prepareSealedTrip();
        // 封账后才按实结算的行程：COMPLETED 但无原清分明细（batchId 为空）
        entry("e7", "T7", "CARD7", "C", "2026-09-25T23:00:00");
        JsonNode lateExit = exit("x7", "T7", "D", "2026-09-25T23:30:00");
        assertTrue(lateExit.get("lateDiscrepancy").asBoolean());
        JsonNode error = refundApply("ra7", "T7", 10, "x", "alice", 422);
        assertEquals("TRIP_NOT_CLEARED", error.get("code").asText());
    }

    @Test
    void sameReviewerAndWrongVersionAndAlreadyConfirmedAreRejected() throws Exception {
        prepareSealedTrip();
        JsonNode applied = refundApply("ra8", "T1", 100, "x", "alice", 200);
        String refundNo = applied.get("refundNo").asText();

        JsonNode sameReviewer = refundConfirm("rc8a", refundNo, "bob", "bob", 1,
                "2026-09-26T10:00:00", 422);
        assertEquals("REVIEWERS_SAME", sameReviewer.get("code").asText());

        JsonNode wrongVersion = refundConfirm("rc8b", refundNo, "bob", "carol", 9,
                "2026-09-26T10:00:00", 409);
        assertEquals("VERSION_MISMATCH", wrongVersion.get("code").asText());

        // 失败不占用状态：仍可用新请求确认成功
        refundConfirm("rc8c", refundNo, "bob", "carol", 1, "2026-09-26T10:00:00", 200);

        // 已双人确认：换 requestId 再确认，单人/重复不生效
        JsonNode again = refundConfirm("rc8d", refundNo, "dave", "erin", 2,
                "2026-09-26T10:05:00", 422);
        assertEquals("REFUND_ALREADY_CONFIRMED", again.get("code").asText());
        // 旧版本号同样冲突
        JsonNode stale = refundConfirm("rc8e", refundNo, "dave", "erin", 1,
                "2026-09-26T10:06:00", 409);
        assertEquals("VERSION_MISMATCH", stale.get("code").asText());
        // 调整单仍只有一笔
        Long orderCount = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM adjustment_order", Long.class);
        assertEquals(1L, orderCount);
    }

    @Test
    void confirmUnknownRefundReturns404AndApplyUnknownTxnReturns404() throws Exception {
        prepareSealedTrip();
        JsonNode unknownRefund = refundConfirm("rc9", "RF-NOPE", "bob", "carol", 1,
                "2026-09-26T10:00:00", 404);
        assertEquals("REFUND_NOT_FOUND", unknownRefund.get("code").asText());
        JsonNode unknownTxn = refundApply("ra9", "TXN-NOPE", 10, "x", "alice", 404);
        assertEquals("TXN_NOT_FOUND", unknownTxn.get("code").asText());
    }

    @Test
    void invalidParamsReturn400() throws Exception {
        prepareSealedTrip();
        // 金额 0：校验失败 400
        JsonNode zeroAmount = call("/api/metro/refunds/apply",
                body("requestId", "ra10", "txnNo", "T1", "amountCents", 0, "reason", "x",
                        "applicant", "alice"), 400);
        assertEquals("VALIDATION_FAILED", zeroAmount.get("code").asText());
        // 缺复核人
        JsonNode blankReviewer = call("/api/metro/refunds/confirm",
                body("requestId", "rc10", "refundNo", "RF1", "reviewer1", "", "reviewer2", "carol",
                        "expectedVersion", 1, "confirmTime", "2026-09-26T10:00:00"), 400);
        assertEquals("VALIDATION_FAILED", blankReviewer.get("code").asText());
    }

    @Test
    void applyIdempotencyReplayConflictAndFailureDoesNotOccupyKey() throws Exception {
        prepareSealedTrip();
        String delimiterReason = "a|b#3:c";
        JsonNode first = refundApply("ra11", "T1", 100, delimiterReason, "alice", 200);
        assertEquals(1, first.get("version").asInt());

        // 同键同参重放
        JsonNode replay = refundApply("ra11", "T1", 100, delimiterReason, "alice", 200);
        assertTrue(replay.get("replayed").asBoolean());
        assertEquals(first.get("refundNo").asText(), replay.get("refundNo").asText());

        // 同键异参 409（reason 含分隔符也必须区分）
        JsonNode conflict = refundApply("ra11", "T1", 100, "other", "alice", 409);
        assertEquals("IDEMPOTENCY_CONFLICT", conflict.get("code").asText());
        JsonNode conflictAmount = refundApply("ra11", "T1", 101, delimiterReason, "alice", 409);
        assertEquals("IDEMPOTENCY_CONFLICT", conflictAmount.get("code").asText());

        Long refundCount = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM refund_order", Long.class);
        assertEquals(1L, refundCount);

        // 失败不占键：先以键 ra12 发起超额退款（422），再以同键合法参数申请成功
        refundApply("ra12", "T1", 9999, "x", "alice", 422);
        JsonNode retry = refundApply("ra12", "T1", 50, "x", "alice", 200);
        assertEquals("PENDING", retry.get("status").asText());
    }

    @Test
    void confirmIdempotencyReplayFreezesFirstResultAndDifferentParamsConflict() throws Exception {
        prepareSealedTrip();
        JsonNode applied = refundApply("ra13", "T1", 100, "x", "alice", 200);
        String refundNo = applied.get("refundNo").asText();
        JsonNode first = refundConfirm("rc13", refundNo, "bob", "carol", 1,
                "2026-09-26T10:00:00", 200);
        String adjustNo = first.get("adjustNo").asText();
        assertTrue(!first.get("replayed").asBoolean());

        JsonNode replay = refundConfirm("rc13", refundNo, "bob", "carol", 1,
                "2026-09-26T10:00:00", 200);
        assertTrue(replay.get("replayed").asBoolean());
        assertEquals(adjustNo, replay.get("adjustNo").asText());
        assertEquals("CONFIRMED", replay.get("status").asText());

        // 同键异参（不同时刻）409
        JsonNode conflict = refundConfirm("rc13", refundNo, "bob", "carol", 1,
                "2026-09-26T10:01:00", 409);
        assertEquals("IDEMPOTENCY_CONFLICT", conflict.get("code").asText());
        Long orderCount = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM adjustment_order", Long.class);
        assertEquals(1L, orderCount);
    }

    @Test
    void sealedAdjustBatchIsReadOnlyAndNewConfirmsGoToNewOpenBatch() throws Exception {
        prepareSealedTrip();
        JsonNode a1 = refundApply("ra14a", "T1", 100, "x", "alice", 200);
        JsonNode c1 = refundConfirm("rc14a", a1.get("refundNo").asText(), "bob", "carol", 1,
                "2026-09-26T10:00:00", 200);
        long firstBatchId = c1.get("batchId").asLong();
        JsonNode sealed = adjustSeal("as14", 1, "2026-09-26T18:00:00", 200);
        assertEquals(-100L, sealed.get("totalCents").asLong());

        // 封存后只读：新确认纳入新开放批次，重复纳入不重复计额
        JsonNode a2 = refundApply("ra14b", "T1", 50, "x", "alice", 200);
        JsonNode c2 = refundConfirm("rc14b", a2.get("refundNo").asText(), "dave", "erin", 1,
                "2026-09-27T10:00:00", 200);
        long secondBatchId = c2.get("batchId").asLong();
        assertTrue(firstBatchId != secondBatchId, "封存后应创建新开放批次");

        JsonNode sealedAgain = adjustSeal("as15", 1, "2026-09-27T18:00:00", 200);
        // 新批次只快照本批次单笔 -50，不含上一批次
        assertEquals(-50L, sealedAgain.get("totalCents").asLong());
        assertEquals(1, sealedAgain.get("orderCount").asInt());

        // 无开放批次时封存：404
        JsonNode none = adjustSeal("as16", 1, "2026-09-27T19:00:00", 404);
        assertEquals("ADJUST_BATCH_NOT_FOUND", none.get("code").asText());
        // 同键重放封存返回首次冻结结果
        JsonNode replay = adjustSeal("as14", 1, "2026-09-26T18:00:00", 200);
        assertTrue(replay.get("replayed").asBoolean());
        assertEquals(-100L, replay.get("totalCents").asLong());

        // 数据库终态：两批次均 SEALED，调整明细合计 -150
        Integer sealedBatches = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM adjustment_batch WHERE status = 'SEALED'", Integer.class);
        assertEquals(2, sealedBatches);
        Long detailSum = jdbcTemplate.queryForObject(
                "SELECT COALESCE(SUM(amount_cents),0) FROM adjustment_detail", Long.class);
        assertEquals(-150L, detailSum);
    }
}
