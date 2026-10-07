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
 * 缴回对账端到端测试（H2 内存库，MySQL 兼容模式）。
 * 基准行程 A->F 16 公里 500 分（原份额 L1=188、L2=312）；退款 300 分双人确认后
 * L1=112、L2=188，纳入调账批次封存，再建立缴回对账单。
 */
@SpringBootTest
@AutoConfigureMockMvc
class RemittanceFlowTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @BeforeEach
    void cleanDatabase() {
        jdbcTemplate.update("DELETE FROM remittance_receipt_detail");
        jdbcTemplate.update("DELETE FROM remittance_receipt");
        jdbcTemplate.update("DELETE FROM remittance_line");
        jdbcTemplate.update("DELETE FROM remittance_source_detail");
        jdbcTemplate.update("DELETE FROM remittance_statement");
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

    /** 构造行程、退款（双人确认）、纳入并封存调账批次；返回调账批次号。 */
    private String sealedAdjustmentBatch(String prefix, String txnNo, String cardId, long refundAmount)
            throws Exception {
        return sealedAdjustmentBatch(prefix, txnNo, cardId, refundAmount, 25);
    }

    /** 构造行程、退款（双人确认）、纳入并封存调账批次，可指定业务日（日）避免跨批次串用。 */
    private String sealedAdjustmentBatch(String prefix, String txnNo, String cardId, long refundAmount,
                                         int day) throws Exception {
        String bizDate = "2026-09-%02d".formatted(day);
        postJson("/api/metro/entry",
                ("{\"requestId\":\"%s-e\",\"txnNo\":\"%s\",\"cardId\":\"%s\",\"station\":\"A\","
                        + "\"entryTime\":\"%sT09:00:00\"}").formatted(prefix, txnNo, cardId, bizDate), 200);
        JsonNode exit = postJson("/api/metro/exit",
                ("{\"requestId\":\"%s-x\",\"txnNo\":\"%s\",\"station\":\"F\","
                        + "\"exitTime\":\"%sT09:40:00\"}").formatted(prefix, txnNo, bizDate), 200);
        assertEquals(500L, exit.get("fareCents").asLong());
        postJson("/api/metro/clearing/seal",
                "{\"requestId\":\"%s-s\",\"bizDate\":\"%s\"}".formatted(prefix, bizDate), 200);
        String refundNo = "R-" + prefix;
        postJson("/api/metro/refunds/apply",
                ("{\"requestId\":\"%s-ra\",\"refundNo\":\"%s\",\"txnNo\":\"%s\",\"amountCents\":%d,"
                        + "\"applyTime\":\"2026-09-26T10:00:00\"}").formatted(prefix, refundNo, txnNo,
                        refundAmount), 200);
        postJson("/api/metro/refunds/confirm",
                ("{\"requestId\":\"%s-rc1\",\"refundNo\":\"%s\",\"reviewer\":\"U1\","
                        + "\"reviewTime\":\"2026-09-26T11:00:00\",\"expectedVersion\":1}")
                        .formatted(prefix, refundNo), 200);
        postJson("/api/metro/refunds/confirm",
                ("{\"requestId\":\"%s-rc2\",\"refundNo\":\"%s\",\"reviewer\":\"U2\","
                        + "\"reviewTime\":\"2026-09-26T11:05:00\",\"expectedVersion\":2}")
                        .formatted(prefix, refundNo), 200);
        String batchNo = "AB-" + prefix;
        postJson("/api/metro/refunds/batches/open",
                ("{\"requestId\":\"%s-ro\",\"batchNo\":\"%s\",\"openTime\":\"2026-09-26T12:00:00\"}")
                        .formatted(prefix, batchNo), 200);
        postJson("/api/metro/refunds/batches/include",
                ("{\"requestId\":\"%s-ri\",\"adjustmentNo\":\"ADJ-%s\",\"batchNo\":\"%s\","
                        + "\"includeTime\":\"2026-09-26T12:05:00\",\"expectedVersion\":1}")
                        .formatted(prefix, refundNo, batchNo), 200);
        JsonNode sealed = postJson("/api/metro/refunds/batches/seal",
                ("{\"requestId\":\"%s-rs\",\"batchNo\":\"%s\",\"sealTime\":\"2026-09-26T13:00:00\","
                        + "\"expectedVersion\":2}").formatted(prefix, batchNo), 200);
        assertEquals(-refundAmount, sealed.get("totalCents").asLong());
        return batchNo;
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

    private String openBody(String requestId, String statementNo, String batchNo) {
        return ("{\"requestId\":\"%s\",\"statementNo\":\"%s\",\"batchNo\":\"%s\","
                + "\"createTime\":\"2026-09-26T14:00:00\"}").formatted(requestId, statementNo, batchNo);
    }

    private String receiptBody(String requestId, String statementNo, String serial, String arrivalUtc,
                               long total, String allocations, int expectedVersion) {
        return ("{\"requestId\":\"%s\",\"statementNo\":\"%s\",\"bankSerialNo\":\"%s\","
                + "\"arrivalTimeUtc\":\"%s\",\"totalCents\":%d,\"allocations\":%s,"
                + "\"expectedVersion\":%d}").formatted(requestId, statementNo, serial, arrivalUtc,
                total, allocations, expectedVersion);
    }

    @Test
    void openStatementFreezesDueFromSealedNegativeAdjustments() throws Exception {
        String batchNo = sealedAdjustmentBatch("f1", "T1", "CARD1", 300);

        JsonNode opened = postJson("/api/metro/remittance/open",
                openBody("rm-open-1", "RM1", batchNo), 200);
        assertEquals("OPEN", opened.get("status").asText());
        assertEquals(300L, opened.get("totalDueCents").asLong());
        assertEquals(1, opened.get("version").asInt());
        assertFalse(opened.get("replayed").asBoolean());
        JsonNode lines = opened.get("lines");
        assertEquals(2, lines.size());
        assertEquals("L1", lines.get(0).get("lineCode").asText());
        assertEquals(112L, lines.get(0).get("dueCents").asLong());
        assertEquals("L2", lines.get(1).get("lineCode").asText());
        assertEquals(188L, lines.get(1).get("dueCents").asLong());

        // 查询：应缴 300、已缴 0、余额 300，空流水链
        JsonNode view = getJson("/api/metro/remittance/RM1", 200);
        assertEquals(300L, view.get("totalDueCents").asLong());
        assertEquals(0L, view.get("totalPaidCents").asLong());
        assertEquals(300L, view.get("balanceCents").asLong());
        assertEquals(0, view.get("receipts").size());
        assertEquals(batchNo, view.get("batchNo").asText());

        // 冻结来源明细独立存在，原调账快照不被覆盖
        Integer frozen = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM remittance_source_detail WHERE due_cents > 0", Integer.class);
        assertEquals(2, frozen);
        Long adjBatchTotal = jdbcTemplate.queryForObject(
                "SELECT total_cents FROM adjustment_batch WHERE batch_no = ?", Long.class, batchNo);
        assertEquals(-300L, adjBatchTotal, "原封账金额不重算、不改写");
    }

    @Test
    void partialThenOutOfOrderThenExactFillCompletesAndNewReceiptRejected() throws Exception {
        String batchNo = sealedAdjustmentBatch("f2", "T2", "CARD2", 300);
        postJson("/api/metro/remittance/open", openBody("rm-open-2", "RM2", batchNo), 200);

        // 第一笔部分到账 100：L1=40、L2=60
        JsonNode r1 = postJson("/api/metro/remittance/receipts",
                receiptBody("rr2-1", "RM2", "BK2-1", "2026-09-27T01:00:00Z", 100,
                        "[{\"lineCode\":\"L1\",\"amountCents\":40},"
                                + "{\"lineCode\":\"L2\",\"amountCents\":60}]", 1), 200);
        assertEquals("OPEN", r1.get("status").asText());
        assertEquals(2, r1.get("version").asInt());
        assertFalse(r1.get("replayed").asBoolean());
        assertEquals("2026-09-27T01:00:00Z", r1.get("arrivalTimeUtc").asText());

        JsonNode view1 = getJson("/api/metro/remittance/RM2", 200);
        assertEquals(100L, view1.get("totalPaidCents").asLong());
        assertEquals(200L, view1.get("balanceCents").asLong());
        assertLine(view1, "L1", 112, 40, 72);
        assertLine(view1, "L2", 188, 60, 128);

        // 第二笔乱序：仅缴 L2 128，L2 缴齐而整单未完成
        JsonNode r2 = postJson("/api/metro/remittance/receipts",
                receiptBody("rr2-2", "RM2", "BK2-2", "2026-09-27T02:00:00Z", 128,
                        "[{\"lineCode\":\"L2\",\"amountCents\":128}]", 2), 200);
        assertEquals("OPEN", r2.get("status").asText());
        assertEquals(3, r2.get("version").asInt());
        JsonNode view2 = getJson("/api/metro/remittance/RM2", 200);
        assertLine(view2, "L1", 112, 40, 72);
        assertLine(view2, "L2", 188, 188, 0);

        // 第三笔恰缴齐 L1 72：全部线路余额为 0，整单完成
        JsonNode r3 = postJson("/api/metro/remittance/receipts",
                receiptBody("rr2-3", "RM2", "BK2-3", "2026-09-27T03:00:00Z", 72,
                        "[{\"lineCode\":\"L1\",\"amountCents\":72}]", 3), 200);
        assertEquals("COMPLETED", r3.get("status").asText());
        assertEquals(4, r3.get("version").asInt());

        JsonNode view3 = getJson("/api/metro/remittance/RM2", 200);
        assertEquals("COMPLETED", view3.get("status").asText());
        assertEquals(300L, view3.get("totalPaidCents").asLong());
        assertEquals(0L, view3.get("balanceCents").asLong());
        // 流水分配链按入账顺序保留三笔
        JsonNode chain = view3.get("receipts");
        assertEquals(3, chain.size());
        assertEquals("BK2-1", chain.get(0).get("bankSerialNo").asText());
        assertEquals(100L, chain.get(0).get("totalCents").asLong());
        assertEquals(2, chain.get(0).get("allocations").size());
        assertEquals("BK2-2", chain.get(1).get("bankSerialNo").asText());
        assertEquals(1, chain.get(1).get("allocations").size());
        assertEquals("BK2-3", chain.get(2).get("bankSerialNo").asText());

        // 完成后新流水拒绝
        JsonNode rejected = postJson("/api/metro/remittance/receipts",
                receiptBody("rr2-4", "RM2", "BK2-4", "2026-09-27T04:00:00Z", 1,
                        "[{\"lineCode\":\"L1\",\"amountCents\":1}]", 4), 422);
        assertEquals("REMITTANCE_COMPLETED", rejected.get("code").asText());
        assertEquals(3, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM remittance_receipt WHERE statement_id ="
                        + " (SELECT id FROM remittance_statement WHERE statement_no='RM2')",
                Integer.class));
    }

    private void assertLine(JsonNode view, String lineCode, long due, long paid, long balance) {
        for (JsonNode line : view.get("lines")) {
            if (lineCode.equals(line.get("lineCode").asText())) {
                assertEquals(due, line.get("dueCents").asLong(), lineCode + " due");
                assertEquals(paid, line.get("paidCents").asLong(), lineCode + " paid");
                assertEquals(balance, line.get("balanceCents").asLong(), lineCode + " balance");
                return;
            }
        }
        throw new IllegalStateException("缺少线路: " + lineCode);
    }

    @Test
    void allocationOffByOnePostsZeroAndVersionUnchanged() throws Exception {
        String batchNo = sealedAdjustmentBatch("f3", "T3", "CARD3", 300);
        postJson("/api/metro/remittance/open", openBody("rm-open-3", "RM3", batchNo), 200);

        // 差一分：分配合计 99 != 总额 100，整笔零入账
        JsonNode offByOne = postJson("/api/metro/remittance/receipts",
                receiptBody("rr3-1", "RM3", "BK3-1", "2026-09-27T01:00:00Z", 100,
                        "[{\"lineCode\":\"L1\",\"amountCents\":39},"
                                + "{\"lineCode\":\"L2\",\"amountCents\":60}]", 1), 422);
        assertEquals("REMITTANCE_ALLOCATION_INVALID", offByOne.get("code").asText());
        assertZeroPosted("RM3", 1);

        // 单笔超线路未缴余额（L1 应缴 112，给 113）：整笔零入账
        JsonNode exceedLine = postJson("/api/metro/remittance/receipts",
                receiptBody("rr3-2", "RM3", "BK3-2", "2026-09-27T01:05:00Z", 113,
                        "[{\"lineCode\":\"L1\",\"amountCents\":113}]", 1), 422);
        assertEquals("REMITTANCE_ALLOCATION_INVALID", exceedLine.get("code").asText());
        assertZeroPosted("RM3", 1);

        // 分配给不属于本单的线路：零入账
        JsonNode unknownLine = postJson("/api/metro/remittance/receipts",
                receiptBody("rr3-3", "RM3", "BK3-3", "2026-09-27T01:10:00Z", 10,
                        "[{\"lineCode\":\"L9\",\"amountCents\":10}]", 1), 422);
        assertEquals("REMITTANCE_ALLOCATION_INVALID", unknownLine.get("code").asText());
        assertZeroPosted("RM3", 1);

        // 同一回执内线路重复：零入账
        JsonNode duplicateLine = postJson("/api/metro/remittance/receipts",
                receiptBody("rr3-4", "RM3", "BK3-4", "2026-09-27T01:15:00Z", 20,
                        "[{\"lineCode\":\"L1\",\"amountCents\":10},"
                                + "{\"lineCode\":\"L1\",\"amountCents\":10}]", 1), 422);
        assertEquals("REMITTANCE_ALLOCATION_INVALID", duplicateLine.get("code").asText());
        assertZeroPosted("RM3", 1);
    }

    private void assertZeroPosted(String statementNo, int expectedVersion) {
        JsonNode view = null;
        try {
            view = getJson("/api/metro/remittance/" + statementNo, 200);
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
        assertEquals(0L, view.get("totalPaidCents").asLong(), "整笔零入账");
        assertEquals(expectedVersion, view.get("version").asInt(), "失败回执不递增版本");
        assertEquals(0, view.get("receipts").size(), "失败不形成流水凭据");
    }

    @Test
    void bankSerialIsGlobalUniqueAndSameContentReplaysEvenWithNewRequestId() throws Exception {
        String batch1 = sealedAdjustmentBatch("f4a", "T4A", "CARD4A", 300, 24);
        String batch2 = sealedAdjustmentBatch("f4b", "T4B", "CARD4B", 100, 25);
        postJson("/api/metro/remittance/open", openBody("rm-open-4a", "RM4A", batch1), 200);
        postJson("/api/metro/remittance/open", openBody("rm-open-4b", "RM4B", batch2), 200);

        String body = receiptBody("rr4-1", "RM4A", "BK4-X", "2026-09-27T01:00:00Z", 100,
                "[{\"lineCode\":\"L1\",\"amountCents\":40},"
                        + "{\"lineCode\":\"L2\",\"amountCents\":60}]", 1);
        JsonNode first = postJson("/api/metro/remittance/receipts", body, 200);
        assertFalse(first.get("replayed").asBoolean());

        // 换批使用同一流水号：409，且不向 RM4B 入账
        JsonNode wrongBatch = postJson("/api/metro/remittance/receipts",
                receiptBody("rr4-2", "RM4B", "BK4-X", "2026-09-27T01:00:00Z", 100,
                        "[{\"lineCode\":\"L1\",\"amountCents\":37},"
                                + "{\"lineCode\":\"L2\",\"amountCents\":63}]", 1), 409);
        assertEquals("DUPLICATE_BANK_SERIAL", wrongBatch.get("code").asText());
        assertEquals(0L, getJson("/api/metro/remittance/RM4B", 200).get("totalPaidCents").asLong());

        // 改金额（同批同流水）：409
        JsonNode changedAmount = postJson("/api/metro/remittance/receipts",
                receiptBody("rr4-3", "RM4A", "BK4-X", "2026-09-27T01:00:00Z", 101,
                        "[{\"lineCode\":\"L1\",\"amountCents\":41},"
                                + "{\"lineCode\":\"L2\",\"amountCents\":60}]", 1), 409);
        assertEquals("BANK_SERIAL_CONFLICT", changedAmount.get("code").asText());

        // 改时刻（同批同流水）：409
        JsonNode changedTime = postJson("/api/metro/remittance/receipts",
                receiptBody("rr4-4", "RM4A", "BK4-X", "2026-09-27T01:00:01Z", 100,
                        "[{\"lineCode\":\"L1\",\"amountCents\":40},"
                                + "{\"lineCode\":\"L2\",\"amountCents\":60}]", 1), 409);
        assertEquals("BANK_SERIAL_CONFLICT", changedTime.get("code").asText());

        // 同流水同完整内容，但绑定新 requestId：重放首次结果
        JsonNode replay = postJson("/api/metro/remittance/receipts",
                receiptBody("rr4-NEW", "RM4A", "BK4-X", "2026-09-27T01:00:00Z", 100,
                        "[{\"lineCode\":\"L1\",\"amountCents\":40},"
                                + "{\"lineCode\":\"L2\",\"amountCents\":60}]", 1), 200);
        assertTrue(replay.get("replayed").asBoolean());
        assertEquals(2, replay.get("version").asInt(), "重放不递增版本");
        assertEquals(100L, getJson("/api/metro/remittance/RM4A", 200).get("totalPaidCents").asLong(),
                "重放不重复入账");

        // 新 requestId 再次同参重放仍命中
        JsonNode replay2 = postJson("/api/metro/remittance/receipts",
                receiptBody("rr4-NEW", "RM4A", "BK4-X", "2026-09-27T01:00:00Z", 100,
                        "[{\"lineCode\":\"L1\",\"amountCents\":40},"
                                + "{\"lineCode\":\"L2\",\"amountCents\":60}]", 1), 200);
        assertTrue(replay2.get("replayed").asBoolean());
    }

    @Test
    void requestIdIdempotencyReplayConflictAndFailureReleasesKey() throws Exception {
        String batchNo = sealedAdjustmentBatch("f5", "T5", "CARD5", 300);
        postJson("/api/metro/remittance/open", openBody("rm-open-5", "RM5", batchNo), 200);

        JsonNode first = postJson("/api/metro/remittance/receipts",
                receiptBody("rr5-1", "RM5", "BK5-1", "2026-09-27T01:00:00Z", 100,
                        "[{\"lineCode\":\"L1\",\"amountCents\":40},"
                                + "{\"lineCode\":\"L2\",\"amountCents\":60}]", 1), 200);
        assertFalse(first.get("replayed").asBoolean());
        // 同 requestId 同参重放
        JsonNode replay = postJson("/api/metro/remittance/receipts",
                receiptBody("rr5-1", "RM5", "BK5-1", "2026-09-27T01:00:00Z", 100,
                        "[{\"lineCode\":\"L1\",\"amountCents\":40},"
                                + "{\"lineCode\":\"L2\",\"amountCents\":60}]", 1), 200);
        assertTrue(replay.get("replayed").asBoolean());
        // 同 requestId 异参（改金额）：409
        JsonNode conflict = postJson("/api/metro/remittance/receipts",
                receiptBody("rr5-1", "RM5", "BK5-2", "2026-09-27T02:00:00Z", 50,
                        "[{\"lineCode\":\"L1\",\"amountCents\":50}]", 1), 409);
        assertEquals("IDEMPOTENCY_CONFLICT", conflict.get("code").asText());

        // 失败不占键：rr5-fail 先差一分失败（此时版本已为 2），再同键合法到账成功
        JsonNode failed = postJson("/api/metro/remittance/receipts",
                receiptBody("rr5-fail", "RM5", "BK5-3", "2026-09-27T03:00:00Z", 50,
                        "[{\"lineCode\":\"L1\",\"amountCents\":25},"
                                + "{\"lineCode\":\"L2\",\"amountCents\":24}]", 2), 422);
        assertEquals("REMITTANCE_ALLOCATION_INVALID", failed.get("code").asText());
        JsonNode retried = postJson("/api/metro/remittance/receipts",
                receiptBody("rr5-fail", "RM5", "BK5-3", "2026-09-27T03:00:00Z", 49,
                        "[{\"lineCode\":\"L1\",\"amountCents\":25},"
                                + "{\"lineCode\":\"L2\",\"amountCents\":24}]", 2), 200);
        assertFalse(retried.get("replayed").asBoolean());

        // 建单同键异参 409；建单失败不占键
        JsonNode openReplay = postJson("/api/metro/remittance/open",
                openBody("rm-open-5", "RM5", batchNo), 200);
        assertTrue(openReplay.get("replayed").asBoolean());
        assertEquals(300L, openReplay.get("totalDueCents").asLong());
    }

    @Test
    void staleExpectedVersionIs409() throws Exception {
        String batchNo = sealedAdjustmentBatch("f6", "T6", "CARD6", 300);
        postJson("/api/metro/remittance/open", openBody("rm-open-6", "RM6", batchNo), 200);
        JsonNode stale = postJson("/api/metro/remittance/receipts",
                receiptBody("rr6-1", "RM6", "BK6-1", "2026-09-27T01:00:00Z", 10,
                        "[{\"lineCode\":\"L1\",\"amountCents\":10}]", 9), 409);
        assertEquals("VERSION_CONFLICT", stale.get("code").asText());
        // 合法版本 1 成功
        postJson("/api/metro/remittance/receipts",
                receiptBody("rr6-2", "RM6", "BK6-2", "2026-09-27T01:05:00Z", 10,
                        "[{\"lineCode\":\"L1\",\"amountCents\":10}]", 1), 200);
        // 版本已到 2，再传 1 即旧版 409
        JsonNode oldVersion = postJson("/api/metro/remittance/receipts",
                receiptBody("rr6-3", "RM6", "BK6-3", "2026-09-27T01:10:00Z", 10,
                        "[{\"lineCode\":\"L1\",\"amountCents\":10}]", 1), 409);
        assertEquals("VERSION_CONFLICT", oldVersion.get("code").asText());
    }

    @Test
    void openRequiresSealedNonEmptyBatchAndOneStatementPerBatch() throws Exception {
        String sealed = sealedAdjustmentBatch("f7", "T7", "CARD7", 300);
        postJson("/api/metro/remittance/open", openBody("rm-open-7", "RM7", sealed), 200);
        // 每批最多一单
        JsonNode duplicate = postJson("/api/metro/remittance/open",
                openBody("rm-open-7b", "RM7B", sealed), 422);
        assertEquals("REMITTANCE_ALREADY_EXISTS", duplicate.get("code").asText());

        // 已封存但为空的调账批次不能建单
        postJson("/api/metro/refunds/batches/open",
                "{\"requestId\":\"ro-empty\",\"batchNo\":\"AB-EMPTY\",\"openTime\":\"2026-09-26T12:00:00\"}",
                200);
        JsonNode sealedEmpty = postJson("/api/metro/refunds/batches/seal",
                "{\"requestId\":\"rs-empty\",\"batchNo\":\"AB-EMPTY\",\"sealTime\":\"2026-09-26T13:00:00\","
                        + "\"expectedVersion\":1}", 200);
        assertEquals(0L, sealedEmpty.get("totalCents").asLong());
        JsonNode empty = postJson("/api/metro/remittance/open",
                openBody("rm-open-empty", "RM-EMPTY", "AB-EMPTY"), 422);
        assertEquals("REMITTANCE_BATCH_EMPTY", empty.get("code").asText());

        // 未封存批次不能建单
        postJson("/api/metro/refunds/batches/open",
                "{\"requestId\":\"ro-open\",\"batchNo\":\"AB-OPEN\",\"openTime\":\"2026-09-26T12:00:00\"}",
                200);
        JsonNode notSealed = postJson("/api/metro/remittance/open",
                openBody("rm-open-open", "RM-OPEN", "AB-OPEN"), 422);
        assertEquals("ADJ_BATCH_NOT_SEALED", notSealed.get("code").asText());

        // 未知批次 / 对账单 404
        assertEquals("ADJ_BATCH_NOT_FOUND", postJson("/api/metro/remittance/open",
                openBody("rm-open-x", "RM-X", "AB-GHOST"), 404).get("code").asText());
        assertEquals("REMITTANCE_NOT_FOUND", getJson("/api/metro/remittance/GHOST", 404)
                .get("code").asText());
        assertEquals("REMITTANCE_NOT_FOUND", postJson("/api/metro/remittance/receipts",
                receiptBody("rr-x", "GHOST", "BK-X", "2026-09-27T01:00:00Z", 1,
                        "[{\"lineCode\":\"L1\",\"amountCents\":1}]", 1), 404).get("code").asText());
    }

    @Test
    void closingOriginalCardDoesNotBlockLawfulRemittanceOrReplay() throws Exception {
        String batchNo = sealedAdjustmentBatch("f8", "T8", "CARD8", 300);
        // 关闭原票卡
        JsonNode closed = postJson("/api/metro/refunds/cards/close",
                "{\"requestId\":\"rk8\",\"cardId\":\"CARD8\",\"closeTime\":\"2026-09-26T13:30:00\","
                        + "\"expectedVersion\":1}", 200);
        assertEquals("CLOSED", closed.get("status").asText());
        // 关卡后建立对账单、合法缴回不受阻，也不重新开放退款额度（退款份额快照不变）
        postJson("/api/metro/remittance/open", openBody("rm-open-8", "RM8", batchNo), 200);
        JsonNode receipt = postJson("/api/metro/remittance/receipts",
                receiptBody("rr8-1", "RM8", "BK8-1", "2026-09-27T01:00:00Z", 300,
                        "[{\"lineCode\":\"L1\",\"amountCents\":112},"
                                + "{\"lineCode\":\"L2\",\"amountCents\":188}]", 1), 200);
        assertEquals("COMPLETED", receipt.get("status").asText());
        // 关卡后成功回执重放
        JsonNode replay = postJson("/api/metro/remittance/receipts",
                receiptBody("rr8-NEW", "RM8", "BK8-1", "2026-09-27T01:00:00Z", 300,
                        "[{\"lineCode\":\"L1\",\"amountCents\":112},"
                                + "{\"lineCode\":\"L2\",\"amountCents\":188}]", 1), 200);
        assertTrue(replay.get("replayed").asBoolean());
        // 退款余额快照未因缴回而重新开放
        JsonNode balance = getJson("/api/metro/refunds/balance/T8", 200);
        assertEquals(300L, balance.get("refundedCents").asLong());
        assertEquals(200L, balance.get("availableCents").asLong());
        JsonNode view = getJson("/api/metro/remittance/RM8", 200);
        assertEquals("COMPLETED", view.get("status").asText());
        assertEquals(300L, view.get("totalPaidCents").asLong());
    }

    @Test
    void invalidReceiptParamsReturn400() throws Exception {
        String batchNo = sealedAdjustmentBatch("f9", "T9", "CARD9", 300);
        postJson("/api/metro/remittance/open", openBody("rm-open-9", "RM9", batchNo), 200);
        // 空分配
        assertEquals("VALIDATION_FAILED", postJson("/api/metro/remittance/receipts",
                receiptBody("rr9-1", "RM9", "BK9-1", "2026-09-27T01:00:00Z", 1, "[]", 1), 400)
                .get("code").asText());
        // 超过 10 条分配
        StringBuilder eleven = new StringBuilder("[");
        for (int i = 0; i < 11; i++) {
            if (i > 0) {
                eleven.append(',');
            }
            eleven.append("{\"lineCode\":\"L").append(i + 1).append("\",\"amountCents\":1}");
        }
        eleven.append(']');
        assertEquals("VALIDATION_FAILED", postJson("/api/metro/remittance/receipts",
                receiptBody("rr9-2", "RM9", "BK9-2", "2026-09-27T01:00:00Z", 11,
                        eleven.toString(), 1), 400).get("code").asText());
        // 金额必须为正
        assertEquals("VALIDATION_FAILED", postJson("/api/metro/remittance/receipts",
                receiptBody("rr9-3", "RM9", "BK9-3", "2026-09-27T01:00:00Z", 0,
                        "[{\"lineCode\":\"L1\",\"amountCents\":0}]", 1), 400).get("code").asText());
        // 必填字段缺失（无时刻）
        assertEquals("VALIDATION_FAILED", postJson("/api/metro/remittance/receipts",
                "{\"requestId\":\"rr9-4\",\"statementNo\":\"RM9\",\"bankSerialNo\":\"BK9-4\","
                        + "\"totalCents\":1,\"allocations\":[{\"lineCode\":\"L1\",\"amountCents\":1}],"
                        + "\"expectedVersion\":1}", 400).get("code").asText());
        // 建单字段为空
        assertEquals("VALIDATION_FAILED", postJson("/api/metro/remittance/open",
                "{\"requestId\":\"\",\"statementNo\":\"RM9B\",\"batchNo\":\"" + batchNo + "\","
                        + "\"createTime\":\"2026-09-26T14:00:00\"}", 400).get("code").asText());
    }
}
