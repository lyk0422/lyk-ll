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
 * 封账后冲正（退款）端到端测试（H2 内存库，MySQL 兼容模式）。
 * 基准行程 A->F 16 公里 500 分，原线路份额 L1=188、L2=312，原批次封账后发起退款。
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
        jdbcTemplate.update("DELETE FROM refund_adjustment_detail");
        jdbcTemplate.update("DELETE FROM refund_adjustment");
        jdbcTemplate.update("DELETE FROM adjustment_batch");
        jdbcTemplate.update("DELETE FROM refund_review");
        jdbcTemplate.update("DELETE FROM refund_line_share");
        jdbcTemplate.update("DELETE FROM refund_order");
        jdbcTemplate.update("DELETE FROM metro_card");
        jdbcTemplate.update("DELETE FROM clearing_detail");
        jdbcTemplate.update("DELETE FROM clearing_discrepancy");
        jdbcTemplate.update("DELETE FROM metro_transaction");
        jdbcTemplate.update("DELETE FROM clearing_batch");
        jdbcTemplate.update("DELETE FROM idempotency_record");
    }

    /** 构造一笔 A->F、已按实结算并已封账的行程（原实收 500 分）。 */
    private void sealedTripAF(String requestPrefix, String txnNo, String cardId) throws Exception {
        postJson("/api/metro/entry",
                ("{\"requestId\":\"%s-e\",\"txnNo\":\"%s\",\"cardId\":\"%s\",\"station\":\"A\","
                        + "\"entryTime\":\"2026-09-25T09:00:00\"}").formatted(requestPrefix, txnNo, cardId),
                200);
        JsonNode exit = postJson("/api/metro/exit",
                ("{\"requestId\":\"%s-x\",\"txnNo\":\"%s\",\"station\":\"F\","
                        + "\"exitTime\":\"2026-09-25T09:40:00\"}").formatted(requestPrefix, txnNo), 200);
        assertEquals(500L, exit.get("fareCents").asLong());
        postJson("/api/metro/clearing/seal",
                "{\"requestId\":\"%s-s\",\"bizDate\":\"2026-09-25\"}".formatted(requestPrefix), 200);
    }

    private JsonNode postJson(String path, String body, int expectedStatus) throws Exception {
        MvcResult result = mockMvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().is(expectedStatus)).andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private JsonNode getJson(String path, int expectedStatus) throws Exception {
        MvcResult result = mockMvc.perform(get(path)).andExpect(status().is(expectedStatus)).andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private String applyBody(String requestId, String refundNo, String txnNo, long amount) {
        return ("{\"requestId\":\"%s\",\"refundNo\":\"%s\",\"txnNo\":\"%s\",\"amountCents\":%d,"
                + "\"applyTime\":\"2026-09-26T10:00:00\"}").formatted(requestId, refundNo, txnNo, amount);
    }

    private String confirmBody(String requestId, String refundNo, String reviewer,
                               String reviewTime, int expectedVersion) {
        return ("{\"requestId\":\"%s\",\"refundNo\":\"%s\",\"reviewer\":\"%s\",\"reviewTime\":\"%s\","
                + "\"expectedVersion\":%d}").formatted(requestId, refundNo, reviewer, reviewTime,
                expectedVersion);
    }

    @Test
    void applyTwoReviewersConfirmAllocateIncludeSealAndOriginalBooksUntouched() throws Exception {
        sealedTripAF("t1", "T1", "CARD1");

        // 退款前余额查询：由封账实结明细推导
        JsonNode balanceBefore = getJson("/api/metro/refunds/balance/T1", 200);
        assertEquals(500L, balanceBefore.get("originalFareCents").asLong());
        assertEquals(0L, balanceBefore.get("refundedCents").asLong());
        assertEquals(500L, balanceBefore.get("availableCents").asLong());

        // 申请退款 100 分
        JsonNode apply = postJson("/api/metro/refunds/apply", applyBody("ra1", "R1", "T1", 100), 200);
        assertEquals("PENDING", apply.get("status").asText());
        assertEquals(1, apply.get("version").asInt());

        // 首人复核：版本 1 -> 2，单人不生效（无调整单）
        JsonNode first = postJson("/api/metro/refunds/confirm",
                confirmBody("rc1a", "R1", "U1", "2026-09-26T11:00:00", 1), 200);
        assertEquals("IN_REVIEW", first.get("status").asText());
        assertEquals(2, first.get("version").asInt());
        assertFalse(first.get("confirmed").asBoolean());
        assertTrue(first.get("adjustmentNo").isNull());
        assertEquals(0, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM refund_adjustment", Integer.class));

        // 最终确认：不同复核人，版本 2 -> 3，分摊 L1=37、L2=63（尾差按份额降序给 L2）
        JsonNode confirmed = postJson("/api/metro/refunds/confirm",
                confirmBody("rc1b", "R1", "U2", "2026-09-26T11:05:00", 2), 200);
        assertEquals("CONFIRMED", confirmed.get("status").asText());
        assertEquals(3, confirmed.get("version").asInt());
        assertTrue(confirmed.get("confirmed").asBoolean());
        assertEquals("ADJ-R1", confirmed.get("adjustmentNo").asText());
        assertEquals("U1", confirmed.get("firstReviewer").asText());
        assertEquals("U2", confirmed.get("finalReviewer").asText());
        JsonNode allocations = confirmed.get("allocations");
        long allocSum = 0;
        for (JsonNode a : allocations) {
            allocSum += a.get("refundCents").asLong();
            if ("L1".equals(a.get("lineCode").asText())) {
                assertEquals(37L, a.get("refundCents").asLong());
            } else {
                assertEquals("L2", a.get("lineCode").asText());
                assertEquals(63L, a.get("refundCents").asLong());
            }
        }
        assertEquals(100L, allocSum);

        // 负向调整明细独立存在，不触碰原清分明细
        Integer negativeDetails = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM refund_adjustment_detail WHERE amount_cents < 0", Integer.class);
        assertEquals(2, negativeDetails);

        // 余额已占用：L1 188/37/151，L2 312/63/249
        JsonNode balance = getJson("/api/metro/refunds/balance/T1", 200);
        assertEquals(500L, balance.get("originalFareCents").asLong());
        assertEquals(100L, balance.get("refundedCents").asLong());
        assertEquals(400L, balance.get("availableCents").asLong());
        for (JsonNode line : balance.get("lines")) {
            switch (line.get("lineCode").asText()) {
                case "L1" -> {
                    assertEquals(188L, line.get("originalShareCents").asLong());
                    assertEquals(37L, line.get("refundedCents").asLong());
                    assertEquals(151L, line.get("availableCents").asLong());
                }
                case "L2" -> {
                    assertEquals(312L, line.get("originalShareCents").asLong());
                    assertEquals(63L, line.get("refundedCents").asLong());
                    assertEquals(249L, line.get("availableCents").asLong());
                }
                default -> throw new IllegalStateException("unexpected line");
            }
        }

        // 开放调账批次并纳入；重复纳入不重复计额
        JsonNode opened = postJson("/api/metro/refunds/batches/open",
                "{\"requestId\":\"ro1\",\"batchNo\":\"AB1\",\"openTime\":\"2026-09-26T12:00:00\"}", 200);
        assertEquals("OPEN", opened.get("status").asText());
        assertEquals(1, opened.get("version").asInt());
        JsonNode included = postJson("/api/metro/refunds/batches/include",
                "{\"requestId\":\"ri1\",\"adjustmentNo\":\"ADJ-R1\",\"batchNo\":\"AB1\","
                        + "\"includeTime\":\"2026-09-26T12:05:00\",\"expectedVersion\":1}", 200);
        assertFalse(included.get("alreadyIncluded").asBoolean());
        assertEquals(2, included.get("version").asInt());
        JsonNode reincluded = postJson("/api/metro/refunds/batches/include",
                "{\"requestId\":\"ri1\",\"adjustmentNo\":\"ADJ-R1\",\"batchNo\":\"AB1\","
                        + "\"includeTime\":\"2026-09-26T12:05:00\",\"expectedVersion\":1}", 200);
        assertTrue(reincluded.get("replayed").asBoolean());
        assertEquals(2, reincluded.get("version").asInt());

        // 封存：合计 -100，调整单 1
        JsonNode sealed = postJson("/api/metro/refunds/batches/seal",
                "{\"requestId\":\"rs1\",\"batchNo\":\"AB1\",\"sealTime\":\"2026-09-26T13:00:00\","
                        + "\"expectedVersion\":2}", 200);
        assertEquals("SEALED", sealed.get("status").asText());
        assertEquals(-100L, sealed.get("totalCents").asLong());
        assertEquals(1, sealed.get("adjustmentCount").asInt());
        assertEquals(3, sealed.get("version").asInt());

        JsonNode batchView = getJson("/api/metro/refunds/batches/AB1", 200);
        assertEquals(-100L, batchView.get("totalCents").asLong());
        assertEquals(1, batchView.get("adjustmentCount").asInt());
        assertEquals(2, batchView.get("details").size());

        // 原封账批次合计与原明细永不改写
        JsonNode originalBatch = getJson("/api/metro/clearing/batches/2026-09-25", 200);
        assertEquals("SEALED", originalBatch.get("status").asText());
        assertEquals(500L, originalBatch.get("totalCents").asLong());
        assertEquals(1, originalBatch.get("txnCount").asInt());
        assertEquals(2, originalBatch.get("details").size());
    }

    @Test
    void refundRejectedBeforeOriginalBatchSealed() throws Exception {
        postJson("/api/metro/entry",
                "{\"requestId\":\"t2-e\",\"txnNo\":\"T2\",\"cardId\":\"CARD2\",\"station\":\"A\","
                        + "\"entryTime\":\"2026-09-25T09:00:00\"}", 200);
        postJson("/api/metro/exit",
                "{\"requestId\":\"t2-x\",\"txnNo\":\"T2\",\"station\":\"F\",\"exitTime\":\"2026-09-25T09:40:00\"}",
                200);
        // 原批次 OPEN：拒绝退款
        JsonNode error = postJson("/api/metro/refunds/apply", applyBody("ra2", "R2", "T2", 100), 422);
        assertEquals("REFUND_BATCH_NOT_SEALED", error.get("code").asText());
    }

    @Test
    void unexitedAndEstimatedTripsCannotBeRefunded() throws Exception {
        postJson("/api/metro/entry",
                "{\"requestId\":\"t3-e\",\"txnNo\":\"T3\",\"cardId\":\"CARD3\",\"station\":\"A\","
                        + "\"entryTime\":\"2026-09-25T09:00:00\"}", 200);
        // 未出闸暂记（ENTERED）不可退
        JsonNode enteredError = postJson("/api/metro/refunds/apply",
                applyBody("ra3a", "R3A", "T3", 10), 422);
        assertEquals("REFUND_TXN_NOT_SETTLED", enteredError.get("code").asText());

        // 出闸缺失暂记（ESTIMATED）同样不可退
        postJson("/api/metro/estimate",
                "{\"requestId\":\"t3-m\",\"txnNo\":\"T3\"}", 200);
        JsonNode estimatedError = postJson("/api/metro/refunds/apply",
                applyBody("ra3b", "R3B", "T3", 10), 422);
        assertEquals("REFUND_TXN_NOT_SETTLED", estimatedError.get("code").asText());
    }

    @Test
    void singleReviewerNeverTakesEffectAndMustDiffer() throws Exception {
        sealedTripAF("t4", "T4", "CARD4");
        postJson("/api/metro/refunds/apply", applyBody("ra4", "R4", "T4", 100), 200);
        postJson("/api/metro/refunds/confirm",
                confirmBody("rc4a", "R4", "U1", "2026-09-26T11:00:00", 1), 200);
        // 同一复核人尝试最终确认：422，单人不生效
        JsonNode error = postJson("/api/metro/refunds/confirm",
                confirmBody("rc4b", "R4", "U1", "2026-09-26T11:05:00", 2), 422);
        assertEquals("REVIEWER_MUST_DIFFER", error.get("code").asText());
        assertEquals(0, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM refund_adjustment", Integer.class));
        // 退款单仍停留在 IN_REVIEW/版本2，余额未占用
        JsonNode balance = getJson("/api/metro/refunds/balance/T4", 200);
        assertEquals(0L, balance.get("refundedCents").asLong());
    }

    @Test
    void cumulativeRefundsCannotExceedOriginalAndExactFullIsLegal() throws Exception {
        sealedTripAF("t5", "T5", "CARD5");
        // 第一笔 300：L1=112，L2=188（尾差给份额更大的 L2）
        postJson("/api/metro/refunds/apply", applyBody("ra5a", "R5A", "T5", 300), 200);
        postJson("/api/metro/refunds/confirm",
                confirmBody("rc5a1", "R5A", "U1", "2026-09-26T11:00:00", 1), 200);
        JsonNode first = postJson("/api/metro/refunds/confirm",
                confirmBody("rc5a2", "R5A", "U2", "2026-09-26T11:05:00", 2), 200);
        assertEquals(112L, first.get("allocations").get(0).get("refundCents").asLong());
        assertEquals(188L, first.get("allocations").get(1).get("refundCents").asLong());

        // 超过可退余额（剩 200）的申请被拒
        JsonNode exceed = postJson("/api/metro/refunds/apply", applyBody("ra5b", "R5B", "T5", 201), 422);
        assertEquals("REFUND_EXCEEDS_AVAILABLE", exceed.get("code").asText());

        // 恰退满 200 合法：floor L1=75、L2=124，尾差 L2 已满则循环给尚有余额的 L1 -> L1=76
        postJson("/api/metro/refunds/apply", applyBody("ra5c", "R5C", "T5", 200), 200);
        postJson("/api/metro/refunds/confirm",
                confirmBody("rc5c1", "R5C", "U1", "2026-09-26T12:00:00", 1), 200);
        JsonNode second = postJson("/api/metro/refunds/confirm",
                confirmBody("rc5c2", "R5C", "U2", "2026-09-26T12:05:00", 2), 200);
        assertEquals(76L, second.get("allocations").get(0).get("refundCents").asLong());
        assertEquals(124L, second.get("allocations").get(1).get("refundCents").asLong());

        JsonNode balance = getJson("/api/metro/refunds/balance/T5", 200);
        assertEquals(500L, balance.get("refundedCents").asLong());
        assertEquals(0L, balance.get("availableCents").asLong());
        // 已退满后再退 1 分被拒
        JsonNode noneLeft = postJson("/api/metro/refunds/apply", applyBody("ra5d", "R5D", "T5", 1), 422);
        assertEquals("REFUND_EXCEEDS_AVAILABLE", noneLeft.get("code").asText());
    }

    @Test
    void idempotentReplayFreezesFirstResultDifferentParamsConflictAndFailureDoesNotOccupyKey()
            throws Exception {
        sealedTripAF("t6", "T6", "CARD6");
        JsonNode first = postJson("/api/metro/refunds/apply", applyBody("ra6", "R6", "T6", 100), 200);
        assertFalse(first.get("replayed").asBoolean());
        // 同键同参：重放首次结果
        JsonNode replay = postJson("/api/metro/refunds/apply", applyBody("ra6", "R6", "T6", 100), 200);
        assertTrue(replay.get("replayed").asBoolean());
        assertEquals(1, replay.get("version").asInt());
        // 同键异参（金额不同）：409，仅一张退款单
        JsonNode conflict = postJson("/api/metro/refunds/apply", applyBody("ra6", "R6", "T6", 200), 409);
        assertEquals("IDEMPOTENCY_CONFLICT", conflict.get("code").asText());
        assertEquals(1, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM refund_order WHERE refund_no = 'R6'", Integer.class));

        // 业务失败不占键：先用 ra7 发起超额退款失败，随后同键合法申请成功
        JsonNode failed = postJson("/api/metro/refunds/apply", applyBody("ra7", "R7", "T6", 9999), 422);
        assertEquals("REFUND_EXCEEDS_AVAILABLE", failed.get("code").asText());
        JsonNode retried = postJson("/api/metro/refunds/apply", applyBody("ra7", "R7", "T6", 50), 200);
        assertFalse(retried.get("replayed").asBoolean());

        // 最终确认成功后，同键同参重放冻结的 CONFIRMED 结果
        postJson("/api/metro/refunds/confirm",
                confirmBody("rc6a", "R6", "U1", "2026-09-26T11:00:00", 1), 200);
        JsonNode confirmed = postJson("/api/metro/refunds/confirm",
                confirmBody("rc6b", "R6", "U2", "2026-09-26T11:05:00", 2), 200);
        assertFalse(confirmed.get("replayed").asBoolean());
        JsonNode confirmReplay = postJson("/api/metro/refunds/confirm",
                confirmBody("rc6b", "R6", "U2", "2026-09-26T11:05:00", 2), 200);
        assertTrue(confirmReplay.get("replayed").asBoolean());
        assertEquals("ADJ-R6", confirmReplay.get("adjustmentNo").asText());
    }

    @Test
    void structuredFingerprintDistinguishesTextsContainingDelimiters() throws Exception {
        sealedTripAF("t8", "T8", "CARD8");
        postJson("/api/metro/refunds/apply", applyBody("ra8", "a", "T8", 100), 200);
        // 首人复核：复核人 "b|c"
        postJson("/api/metro/refunds/confirm",
                confirmBody("rc8", "a", "b|c", "2026-09-26T11:00:00", 1), 200);
        // 同键但退款单号/复核人重新切分（管道符拼接指纹会碰撞，结构化指纹不会）
        JsonNode conflict = postJson("/api/metro/refunds/confirm",
                confirmBody("rc8", "a|b", "c", "2026-09-26T11:00:00", 1), 409);
        assertEquals("IDEMPOTENCY_CONFLICT", conflict.get("code").asText());
    }

    @Test
    void expectedVersionConflictsAre409() throws Exception {
        sealedTripAF("t9", "T9", "CARD9");
        postJson("/api/metro/refunds/apply", applyBody("ra9", "R9", "T9", 100), 200);
        // 首人复核必须传版本 1
        JsonNode wrongFirst = postJson("/api/metro/refunds/confirm",
                confirmBody("rc9a", "R9", "U1", "2026-09-26T11:00:00", 2), 409);
        assertEquals("VERSION_CONFLICT", wrongFirst.get("code").asText());
        postJson("/api/metro/refunds/confirm",
                confirmBody("rc9b", "R9", "U1", "2026-09-26T11:00:00", 1), 200);
        // 最终确认必须传版本 2
        JsonNode wrongFinal = postJson("/api/metro/refunds/confirm",
                confirmBody("rc9c", "R9", "U2", "2026-09-26T11:05:00", 1), 409);
        assertEquals("VERSION_CONFLICT", wrongFinal.get("code").asText());
        postJson("/api/metro/refunds/confirm",
                confirmBody("rc9d", "R9", "U2", "2026-09-26T11:05:00", 2), 200);

        // 已 CONFIRMED 再以新键确认：业务拒绝，不可重复生效
        JsonNode already = postJson("/api/metro/refunds/confirm",
                confirmBody("rc9e", "R9", "U3", "2026-09-26T11:10:00", 3), 422);
        assertEquals("REFUND_ALREADY_CONFIRMED", already.get("code").asText());

        postJson("/api/metro/refunds/batches/open",
                "{\"requestId\":\"ro9\",\"batchNo\":\"AB9\",\"openTime\":\"2026-09-26T12:00:00\"}", 200);
        JsonNode wrongIncludeVersion = postJson("/api/metro/refunds/batches/include",
                "{\"requestId\":\"ri9\",\"adjustmentNo\":\"ADJ-R9\",\"batchNo\":\"AB9\","
                        + "\"includeTime\":\"2026-09-26T12:05:00\",\"expectedVersion\":5}", 409);
        assertEquals("VERSION_CONFLICT", wrongIncludeVersion.get("code").asText());
    }

    @Test
    void sealedAdjustmentBatchIsReadOnlyAndAdjustmentBelongsToSingleBatch() throws Exception {
        sealedTripAF("t10", "T10", "CARD10");
        postJson("/api/metro/refunds/apply", applyBody("ra10", "R10", "T10", 100), 200);
        postJson("/api/metro/refunds/confirm",
                confirmBody("rc10a", "R10", "U1", "2026-09-26T11:00:00", 1), 200);
        postJson("/api/metro/refunds/confirm",
                confirmBody("rc10b", "R10", "U2", "2026-09-26T11:05:00", 2), 200);
        postJson("/api/metro/refunds/batches/open",
                "{\"requestId\":\"ro10a\",\"batchNo\":\"AB10A\",\"openTime\":\"2026-09-26T12:00:00\"}", 200);
        postJson("/api/metro/refunds/batches/open",
                "{\"requestId\":\"ro10b\",\"batchNo\":\"AB10B\",\"openTime\":\"2026-09-26T12:01:00\"}", 200);
        postJson("/api/metro/refunds/batches/include",
                "{\"requestId\":\"ri10a\",\"adjustmentNo\":\"ADJ-R10\",\"batchNo\":\"AB10A\","
                        + "\"includeTime\":\"2026-09-26T12:05:00\",\"expectedVersion\":1}", 200);
        // 一张调整单只能纳入一个批次
        JsonNode secondBatch = postJson("/api/metro/refunds/batches/include",
                "{\"requestId\":\"ri10b\",\"adjustmentNo\":\"ADJ-R10\",\"batchNo\":\"AB10B\","
                        + "\"includeTime\":\"2026-09-26T12:06:00\",\"expectedVersion\":1}", 422);
        assertEquals("ADJUSTMENT_ALREADY_IN_BATCH", secondBatch.get("code").asText());
        // 封存 AB10A
        postJson("/api/metro/refunds/batches/seal",
                "{\"requestId\":\"rs10\",\"batchNo\":\"AB10A\",\"sealTime\":\"2026-09-26T13:00:00\","
                        + "\"expectedVersion\":2}", 200);
        // 封存后只读：重复纳入拒绝（用新键，不被幂等重放吸收）
        JsonNode sealedReject = postJson("/api/metro/refunds/batches/include",
                "{\"requestId\":\"ri10c\",\"adjustmentNo\":\"ADJ-R10\",\"batchNo\":\"AB10A\","
                        + "\"includeTime\":\"2026-09-26T13:05:00\",\"expectedVersion\":3}", 422);
        assertEquals("ADJ_BATCH_SEALED", sealedReject.get("code").asText());
        // 封存幂等：同键同参重放终态快照
        JsonNode reseal = postJson("/api/metro/refunds/batches/seal",
                "{\"requestId\":\"rs10\",\"batchNo\":\"AB10A\",\"sealTime\":\"2026-09-26T13:00:00\","
                        + "\"expectedVersion\":2}", 200);
        assertTrue(reseal.get("replayed").asBoolean());
        assertEquals(-100L, reseal.get("totalCents").asLong());
    }

    @Test
    void closingCardDoesNotAffectReplayOfConfirmedRefund() throws Exception {
        sealedTripAF("t11", "T11", "CARD11");
        postJson("/api/metro/refunds/apply", applyBody("ra11", "R11", "T11", 100), 200);
        postJson("/api/metro/refunds/confirm",
                confirmBody("rc11a", "R11", "U1", "2026-09-26T11:00:00", 1), 200);
        postJson("/api/metro/refunds/confirm",
                confirmBody("rc11b", "R11", "U2", "2026-09-26T11:05:00", 2), 200);

        JsonNode closed = postJson("/api/metro/refunds/cards/close",
                "{\"requestId\":\"rk1\",\"cardId\":\"CARD11\",\"closeTime\":\"2026-09-26T14:00:00\","
                        + "\"expectedVersion\":1}", 200);
        assertEquals("CLOSED", closed.get("status").asText());
        assertEquals(2, closed.get("version").asInt());
        // 关卡后：已成功退款的申请与确认历史重放不受影响
        JsonNode applyReplay = postJson("/api/metro/refunds/apply", applyBody("ra11", "R11", "T11", 100), 200);
        assertTrue(applyReplay.get("replayed").asBoolean());
        JsonNode confirmReplay = postJson("/api/metro/refunds/confirm",
                confirmBody("rc11b", "R11", "U2", "2026-09-26T11:05:00", 2), 200);
        assertTrue(confirmReplay.get("replayed").asBoolean());
        assertEquals("CONFIRMED", confirmReplay.get("status").asText());
        // 关卡幂等：同键重放
        JsonNode closeReplay = postJson("/api/metro/refunds/cards/close",
                "{\"requestId\":\"rk1\",\"cardId\":\"CARD11\",\"closeTime\":\"2026-09-26T14:00:00\","
                        + "\"expectedVersion\":1}", 200);
        assertTrue(closeReplay.get("replayed").asBoolean());
        // 旧版本号关闭：版本冲突
        JsonNode stale = postJson("/api/metro/refunds/cards/close",
                "{\"requestId\":\"rk2\",\"cardId\":\"CARD11\",\"closeTime\":\"2026-09-26T14:05:00\","
                        + "\"expectedVersion\":1}", 409);
        assertEquals("VERSION_CONFLICT", stale.get("code").asText());
    }

    @Test
    void missingObjectsReturn404AndInvalidParamsReturn400() throws Exception {
        sealedTripAF("t12", "T12", "CARD12");
        assertEquals("TXN_NOT_FOUND",
                postJson("/api/metro/refunds/apply", applyBody("ra12", "R12", "NO-SUCH-TXN", 10), 404)
                        .get("code").asText());
        assertEquals("REFUND_NOT_FOUND",
                postJson("/api/metro/refunds/confirm",
                        confirmBody("rc12", "NO-SUCH-REFUND", "U1", "2026-09-26T11:00:00", 1), 404)
                        .get("code").asText());
        assertEquals("TXN_NOT_FOUND", getJson("/api/metro/refunds/balance/NO-SUCH-TXN", 404)
                .get("code").asText());
        assertEquals("ADJ_BATCH_NOT_FOUND", getJson("/api/metro/refunds/batches/NO-BATCH", 404)
                .get("code").asText());
        assertEquals("CARD_NOT_FOUND", postJson("/api/metro/refunds/cards/close",
                "{\"requestId\":\"rk12\",\"cardId\":\"GHOST\",\"closeTime\":\"2026-09-26T14:00:00\","
                        + "\"expectedVersion\":1}", 404).get("code").asText());

        // 参数非法：金额必须为正整数、字段不得为空
        assertEquals("VALIDATION_FAILED", postJson("/api/metro/refunds/apply",
                applyBody("ra12b", "R12B", "T12", 0), 400).get("code").asText());
        assertEquals("VALIDATION_FAILED", postJson("/api/metro/refunds/apply",
                "{\"requestId\":\"\",\"refundNo\":\"R12C\",\"txnNo\":\"T12\",\"amountCents\":10,"
                        + "\"applyTime\":\"2026-09-26T10:00:00\"}", 400).get("code").asText());
        assertEquals("VALIDATION_FAILED", postJson("/api/metro/refunds/batches/seal",
                "{\"requestId\":\"rs12\",\"batchNo\":\"AB12\",\"sealTime\":\"2026-09-26T13:00:00\","
                        + "\"expectedVersion\":0}", 400).get("code").asText());
    }
}
