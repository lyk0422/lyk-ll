package com.example.starter.metro;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 封账行程退款冲正主流程、失败分支与幂等边界测试（H2 内存库，MySQL 兼容模式）。
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
        jdbcTemplate.update("DELETE FROM metro_card_close");
        jdbcTemplate.update("DELETE FROM clearing_detail");
        jdbcTemplate.update("DELETE FROM clearing_discrepancy");
        jdbcTemplate.update("DELETE FROM metro_transaction");
        jdbcTemplate.update("DELETE FROM clearing_batch");
        jdbcTemplate.update("DELETE FROM idempotency_record");
    }

    private JsonNode call(String path, String body, int expectedStatus) throws Exception {
        MvcResult result = mockMvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().is(expectedStatus)).andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    /** 进闸→出闸→封账，构造已封账且按实结算的行程。 */
    private void sealedTrip(String requestPrefix, String txnNo, String cardId, String entryStation,
                            String exitStation, String day) throws Exception {
        call("/api/metro/entry",
                "{\"requestId\":\"%s-e\",\"txnNo\":\"%s\",\"cardId\":\"%s\",\"station\":\"%s\",\"entryTime\":\"%sT09:00:00\"}"
                        .formatted(requestPrefix, txnNo, cardId, entryStation, day), 200);
        call("/api/metro/exit",
                "{\"requestId\":\"%s-x\",\"txnNo\":\"%s\",\"station\":\"%s\",\"exitTime\":\"%sT09:30:00\"}"
                        .formatted(requestPrefix, txnNo, exitStation, day), 200);
        call("/api/metro/clearing/seal",
                "{\"requestId\":\"%s-s\",\"bizDate\":\"%s\"}".formatted(requestPrefix, day), 200);
    }

    private JsonNode apply(String requestId, String refundNo, String txnNo, long amount,
                           String applyTime, int expectedStatus) throws Exception {
        return call("/api/metro/refunds/apply",
                "{\"requestId\":\"%s\",\"refundNo\":\"%s\",\"txnNo\":\"%s\",\"amountCents\":%d,\"applyTime\":\"%s\"}"
                        .formatted(requestId, refundNo, txnNo, amount, applyTime), expectedStatus);
    }

    private JsonNode confirm(String requestId, String refundNo, int expectedVersion, String c1,
                             String t1, String c2, String t2, int expectedStatus) throws Exception {
        String body = ("{\"requestId\":\"%s\",\"refundNo\":\"%s\",\"expectedVersion\":%d,"
                + "\"confirmerOne\":\"%s\",\"confirmerOneTime\":\"%s\","
                + "\"confirmerTwo\":\"%s\",\"confirmerTwoTime\":\"%s\"}")
                .formatted(requestId, refundNo, expectedVersion, c1, t1, c2, t2);
        return call("/api/metro/refunds/confirm", body, expectedStatus);
    }

    private JsonNode include(String requestId, String adjustmentNo, int expectedVersion,
                             int expectedStatus) throws Exception {
        return call("/api/metro/refunds/adjustments/include",
                "{\"requestId\":\"%s\",\"adjustmentNo\":\"%s\",\"expectedVersion\":%d}"
                        .formatted(requestId, adjustmentNo, expectedVersion), expectedStatus);
    }

    private JsonNode sealBatch(String requestId, String batchNo, int expectedVersion,
                               int expectedStatus) throws Exception {
        return call("/api/metro/refunds/adjustment-batches/seal",
                "{\"requestId\":\"%s\",\"batchNo\":\"%s\",\"expectedVersion\":%d}"
                        .formatted(requestId, batchNo, expectedVersion), expectedStatus);
    }

    private JsonNode getBalance(String txnNo) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/metro/refunds/txns/" + txnNo + "/balance"))
                .andExpect(status().isOk()).andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    @Test
    void fullRefundFlowAllocatesIncludesAndSealsWithOriginalBatchUntouched() throws Exception {
        // A->F 16 公里 5 元：L1 188 分、L2 312 分
        sealedTrip("p1", "T-1", "CARD-1", "A", "F", "2026-09-25");

        JsonNode balance0 = getBalance("T-1");
        assertEquals(500L, balance0.get("originalCents").asLong());
        assertEquals(0L, balance0.get("refundedCents").asLong());
        assertEquals(500L, balance0.get("refundableCents").asLong());
        assertEquals(2, balance0.get("lines").size());

        // 申请退款 300 分
        JsonNode applied = apply("p1-apply", "R-1", "T-1", 300, "2026-09-26T10:00:00", 200);
        assertEquals("PENDING", applied.get("status").asText());
        assertEquals(1, applied.get("version").asInt());
        assertFalse(applied.get("replayed").asBoolean());
        // 申请阶段不占用可退余额
        assertEquals(500L, getBalance("T-1").get("refundableCents").asLong());

        // 双人确认：300 按份额 -> L1 floor(300*188/500)=112, L2 floor(300*312/500)=187，余 1 分给 L2
        JsonNode confirmed = confirm("p1-confirm", "R-1", 1, "U100", "2026-09-26T10:05:00",
                "U200", "2026-09-26T10:06:00", 200);
        assertEquals("CONFIRMED", confirmed.get("status").asText());
        assertEquals(2, confirmed.get("version").asInt());
        assertEquals("ADJ-R-1", confirmed.get("adjustmentNo").asText());
        long allocated = 0;
        for (JsonNode line : confirmed.get("lineAdjustments")) {
            allocated += line.get("amountCents").asLong();
            if ("L1".equals(line.get("lineCode").asText())) {
                assertEquals(112L, line.get("amountCents").asLong());
            } else {
                assertEquals(188L, line.get("amountCents").asLong());
            }
        }
        assertEquals(300L, allocated);

        JsonNode balance1 = getBalance("T-1");
        assertEquals(300L, balance1.get("refundedCents").asLong());
        assertEquals(200L, balance1.get("refundableCents").asLong());

        // 独立负向明细，原清分明细与封账合计不改写
        Integer negativeDetails = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM adjustment_detail WHERE adjustment_no = 'ADJ-R-1'", Integer.class);
        assertEquals(2, negativeDetails);
        Long negativeSum = jdbcTemplate.queryForObject(
                "SELECT SUM(amount_cents) FROM adjustment_detail WHERE adjustment_no = 'ADJ-R-1'",
                Long.class);
        assertEquals(-300L, negativeSum);
        Long originalDetails = jdbcTemplate.queryForObject(
                "SELECT COALESCE(SUM(amount_cents),0) FROM clearing_detail WHERE txn_no = 'T-1'",
                Long.class);
        assertEquals(500L, originalDetails);
        Long sealedTotal = jdbcTemplate.queryForObject(
                "SELECT total_cents FROM clearing_batch WHERE biz_date = DATE '2026-09-25'",
                Long.class);
        assertEquals(500L, sealedTotal, "原封账合计永不改写");

        // 纳入开放调账批次（首次自动创建）
        JsonNode included = include("p1-include", "ADJ-R-1", 1, 200);
        assertEquals("INCLUDED", included.get("status").asText());
        assertEquals(2, included.get("version").asInt());
        String batchNo = included.get("batchNo").asText();

        // 重复纳入（同键重放）不重复计额
        JsonNode replayInclude = include("p1-include", "ADJ-R-1", 1, 200);
        assertTrue(replayInclude.get("replayed").asBoolean());
        assertEquals(batchNo, replayInclude.get("batchNo").asText());

        JsonNode sealed = sealBatch("p1-seal", batchNo, 1, 200);
        assertEquals("SEALED", sealed.get("status").asText());
        assertEquals(-300L, sealed.get("totalCents").asLong());
        assertEquals(1, sealed.get("adjustmentCount").asInt());
        assertEquals(2, sealed.get("version").asInt());

        // 封存后只读：重复封存幂等成功，合计不变
        JsonNode reseal = sealBatch("p1-seal", batchNo, 1, 200);
        assertTrue(reseal.get("replayed").asBoolean());
        assertEquals(-300L, reseal.get("totalCents").asLong());

        // 封存后再纳入另一调账单：自动另开新批次，不能进入已封存批次
        apply("p1-apply2", "R-2", "T-1", 200, "2026-09-26T11:00:00", 200);
        JsonNode confirmed2 = confirm("p1-confirm2", "R-2", 1, "U101", "2026-09-26T11:05:00",
                "U201", "2026-09-26T11:06:00", 200);
        JsonNode included2 = include("p1-include2",
                confirmed2.get("adjustmentNo").asText(), 1, 200);
        assertFalse(batchNo.equals(included2.get("batchNo").asText()),
                "已封存批次不可再纳入，应自动另开开放批次");

        // 恰退满：第二次退 200，累计 500 恰等于原实收
        JsonNode balance2 = getBalance("T-1");
        assertEquals(500L, balance2.get("refundedCents").asLong());
        assertEquals(0L, balance2.get("refundableCents").asLong());
    }

    @Test
    void refundRequestsAreIdempotentAndDifferentParamsReturn409WithoutOccupyingKey() throws Exception {
        sealedTrip("p2", "T-2", "CARD-2", "A", "D", "2026-09-25");

        JsonNode first = apply("p2-key", "R-2A", "T-2", 100, "2026-09-26T10:00:00", 200);
        assertFalse(first.get("replayed").asBoolean());
        JsonNode replay = apply("p2-key", "R-2A", "T-2", 100, "2026-09-26T10:00:00", 200);
        assertTrue(replay.get("replayed").asBoolean());
        assertEquals("R-2A", replay.get("refundNo").asText());

        // 同键异参 409
        JsonNode conflict = apply("p2-key", "R-2A", "T-2", 200, "2026-09-26T10:00:00", 409);
        assertEquals("IDEMPOTENCY_CONFLICT", conflict.get("code").asText());

        // 申请失败不占键：业务拒绝后换一个合法申请体仍可成功（同键此前仅成功过一次，故换新键）
        JsonNode rejected = apply("p2-fail", "R-2B", "T-2", 9999, "2026-09-26T10:00:00", 422);
        assertEquals("REFUND_EXCEEDS_RECEIVED", rejected.get("code").asText());
        // 失败事务回滚，未留退款单
        Integer refundCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM refund_order WHERE refund_no = 'R-2B'", Integer.class);
        assertEquals(0, refundCount);
        // 同键以合法参数重试可成功，证明失败不占键
        JsonNode retry = apply("p2-fail", "R-2B", "T-2", 100, "2026-09-26T10:01:00", 200);
        assertEquals("PENDING", retry.get("status").asText());
    }

    @Test
    void confirmRejectsSingleReviewerWrongVersionAndAlreadyConfirmed() throws Exception {
        sealedTrip("p3", "T-3", "CARD-3", "A", "D", "2026-09-25");
        apply("p3-apply", "R-3", "T-3", 100, "2026-09-26T10:00:00", 200);

        // 两名复核人相同：422，单人不生效
        JsonNode same = confirm("p3-c-bad", "R-3", 1, "U100", "2026-09-26T10:05:00",
                "U100", "2026-09-26T10:06:00", 422);
        assertEquals("CONFIRMERS_MUST_DIFFER", same.get("code").asText());
        Integer stillPending = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM refund_order WHERE refund_no = 'R-3' AND status = 'PENDING'",
                Integer.class);
        assertEquals(1, stillPending, "单人确认不得生效");

        // 版本不匹配：409
        JsonNode wrongVersion = confirm("p3-c-ver", "R-3", 9, "U100", "2026-09-26T10:05:00",
                "U200", "2026-09-26T10:06:00", 409);
        assertEquals("VERSION_CONFLICT", wrongVersion.get("code").asText());

        // 合法确认成功
        JsonNode ok = confirm("p3-c-ok", "R-3", 1, "U100", "2026-09-26T10:05:00",
                "U200", "2026-09-26T10:06:00", 200);
        assertEquals(2, ok.get("version").asInt());

        // 重复确认（另一键、旧版本）：409
        JsonNode again = confirm("p3-c-again", "R-3", 1, "U100", "2026-09-26T10:05:00",
                "U200", "2026-09-26T10:06:00", 409);
        assertEquals("VERSION_CONFLICT", again.get("code").asText());

        // 同键同参重放首次冻结结果
        JsonNode replay = confirm("p3-c-ok", "R-3", 1, "U100", "2026-09-26T10:05:00",
                "U200", "2026-09-26T10:06:00", 200);
        assertTrue(replay.get("replayed").asBoolean());
        assertEquals("ADJ-R-3", replay.get("adjustmentNo").asText());
    }

    @Test
    void refundRequiresSealedSettledTripAndKnownEntities() throws Exception {
        // 未出闸暂记行程不能退款
        call("/api/metro/entry",
                "{\"requestId\":\"p4-e\",\"txnNo\":\"T-4E\",\"cardId\":\"CARD-4\",\"station\":\"A\",\"entryTime\":\"2026-09-25T09:00:00\"}",
                200);
        JsonNode entered = apply("p4-a1", "R-4E", "T-4E", 10, "2026-09-26T10:00:00", 422);
        assertEquals("REFUND_TXN_NOT_SETTLED", entered.get("code").asText());

        call("/api/metro/estimate",
                "{\"requestId\":\"p4-m\",\"txnNo\":\"T-4E\"}", 200);
        JsonNode estimated = apply("p4-a2", "R-4E", "T-4E", 10, "2026-09-26T10:00:00", 422);
        assertEquals("REFUND_TXN_NOT_SETTLED", estimated.get("code").asText());

        // 已结算但未封账不能退款
        call("/api/metro/entry",
                "{\"requestId\":\"p4-e2\",\"txnNo\":\"T-4O\",\"cardId\":\"CARD-4\",\"station\":\"A\",\"entryTime\":\"2026-09-25T08:00:00\"}",
                200);
        call("/api/metro/exit",
                "{\"requestId\":\"p4-x2\",\"txnNo\":\"T-4O\",\"station\":\"D\",\"exitTime\":\"2026-09-25T08:30:00\"}",
                200);
        JsonNode unsealed = apply("p4-a3", "R-4O", "T-4O", 10, "2026-09-26T10:00:00", 422);
        assertEquals("REFUND_TXN_NOT_SEALED", unsealed.get("code").asText());

        // 对象不存在 404
        assertEquals("TXN_NOT_FOUND",
                apply("p4-a4", "R-4X", "T-NOPE", 10, "2026-09-26T10:00:00", 404).get("code").asText());
        assertEquals("REFUND_NOT_FOUND",
                confirm("p4-cx", "R-NOPE", 1, "U1", "2026-09-26T10:05:00", "U2",
                        "2026-09-26T10:06:00", 404).get("code").asText());
        MvcResult incResult = mockMvc.perform(post("/api/metro/refunds/adjustments/include")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"p4-i\",\"adjustmentNo\":\"ADJ-NOPE\",\"expectedVersion\":1}"))
                .andExpect(status().isNotFound()).andReturn();
        assertEquals("ADJUSTMENT_NOT_FOUND",
                objectMapper.readTree(incResult.getResponse().getContentAsString()).get("code").asText());
        assertEquals("ADJUSTMENT_BATCH_NOT_FOUND",
                sealBatch("p4-seal", "AB-NOPE", 1, 404).get("code").asText());
        MvcResult balResult = mockMvc.perform(get("/api/metro/refunds/txns/T-NOPE/balance"))
                .andExpect(status().isNotFound()).andReturn();
        assertEquals("TXN_NOT_FOUND",
                objectMapper.readTree(balResult.getResponse().getContentAsString()).get("code").asText());
    }

    @Test
    void invalidParametersReturn400AndExactZeroIsRejected() throws Exception {
        sealedTrip("p5", "T-5", "CARD-5", "A", "D", "2026-09-25");
        // 金额 0：非法 400
        JsonNode zero = call("/api/metro/refunds/apply",
                "{\"requestId\":\"p5-zero\",\"refundNo\":\"R-5Z\",\"txnNo\":\"T-5\",\"amountCents\":0,\"applyTime\":\"2026-09-26T10:00:00\"}",
                400);
        assertEquals("VALIDATION_FAILED", zero.get("code").asText());
        // 金额负数：非法 400
        JsonNode negative = call("/api/metro/refunds/apply",
                "{\"requestId\":\"p5-neg\",\"refundNo\":\"R-5N\",\"txnNo\":\"T-5\",\"amountCents\":-5,\"applyTime\":\"2026-09-26T10:00:00\"}",
                400);
        assertEquals("VALIDATION_FAILED", negative.get("code").asText());
        // 缺字段：非法 400
        JsonNode missing = call("/api/metro/refunds/apply",
                "{\"requestId\":\"p5-miss\",\"txnNo\":\"T-5\",\"amountCents\":100}", 400);
        assertEquals("VALIDATION_FAILED", missing.get("code").asText());
    }

    @Test
    void cumulativeRefundMustNotExceedOriginalAndPerLineCapsHold() throws Exception {
        // A->F：L1 188、L2 312
        sealedTrip("p6", "T-6", "CARD-6", "A", "F", "2026-09-25");
        apply("p6-a1", "R-6A", "T-6", 400, "2026-09-26T10:00:00", 200);
        confirm("p6-c1", "R-6A", 1, "U100", "2026-09-26T10:05:00", "U200",
                "2026-09-26T10:06:00", 200);
        JsonNode balanceAfter = getBalance("T-6");
        assertEquals(100L, balanceAfter.get("refundableCents").asLong());

        // 再申请 101 分：超出可退余额 422
        JsonNode over = apply("p6-a2", "R-6B", "T-6", 101, "2026-09-26T11:00:00", 422);
        assertEquals("REFUND_EXCEEDS_RECEIVED", over.get("code").asText());

        // 并发场景的保护也体现在确认时：先申请 100（恰满），另一笔历史申请 1 分确认必被拒
        apply("p6-a3", "R-6C", "T-6", 100, "2026-09-26T11:01:00", 200);
        apply("p6-a4", "R-6D", "T-6", 1, "2026-09-26T11:02:00", 200);
        confirm("p6-c3", "R-6C", 1, "U101", "2026-09-26T11:05:00", "U201",
                "2026-09-26T11:06:00", 200);
        JsonNode confirmRejected = confirm("p6-c4", "R-6D", 1, "U102",
                "2026-09-26T11:07:00", "U202", "2026-09-26T11:08:00", 422);
        assertEquals("REFUND_EXCEEDS_RECEIVED", confirmRejected.get("code").asText());
        // 被拒退款单不产生调账单与负向明细
        Integer strayAdjustment = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM adjustment_order WHERE refund_no = 'R-6D'", Integer.class);
        assertEquals(0, strayAdjustment);
    }

    @Test
    void closingCardDoesNotChangeSuccessfulRefundReplay() throws Exception {
        sealedTrip("p7", "T-7", "CARD-7", "A", "D", "2026-09-25");
        apply("p7-apply", "R-7", "T-7", 100, "2026-09-26T10:00:00", 200);
        confirm("p7-confirm", "R-7", 1, "U100", "2026-09-26T10:05:00", "U200",
                "2026-09-26T10:06:00", 200);

        JsonNode close = call("/api/metro/refunds/cards/close",
                "{\"requestId\":\"p7-close\",\"cardId\":\"CARD-7\",\"closeTime\":\"2026-09-26T12:00:00\"}",
                200);
        assertTrue(close.get("closed").asBoolean());
        // 关闭票卡同键重放
        JsonNode closeReplay = call("/api/metro/refunds/cards/close",
                "{\"requestId\":\"p7-close\",\"cardId\":\"CARD-7\",\"closeTime\":\"2026-09-26T12:00:00\"}",
                200);
        assertTrue(closeReplay.get("replayed").asBoolean());

        // 已成功退款结果仍可历史重放，内容不变
        JsonNode replay = confirm("p7-confirm", "R-7", 1, "U100", "2026-09-26T10:05:00",
                "U200", "2026-09-26T10:06:00", 200);
        assertTrue(replay.get("replayed").asBoolean());
        assertEquals(100L, replay.get("amountCents").asLong());
        assertEquals("ADJ-R-7", replay.get("adjustmentNo").asText());
        assertEquals(100L, getBalance("T-7").get("refundedCents").asLong());
    }
}
