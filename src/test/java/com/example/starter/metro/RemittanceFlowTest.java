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
 * 基准：行程 A->F 500 分（L1=188、L2=312），退款 100 分（L1=37、L2=63），
 * 调账批次封存后建立对账单：应缴 L1=37、L2=63，合计 100 分。
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
        jdbcTemplate.update("DELETE FROM remittance_receipt_line");
        jdbcTemplate.update("DELETE FROM remittance_receipt");
        jdbcTemplate.update("DELETE FROM remittance_source_detail");
        jdbcTemplate.update("DELETE FROM remittance_statement_line");
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

    private JsonNode postJson(String path, String body, int expectedStatus) throws Exception {
        MvcResult result = mockMvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().is(expectedStatus)).andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private JsonNode getJson(String path, int expectedStatus) throws Exception {
        MvcResult result = mockMvc.perform(get(path)).andExpect(status().is(expectedStatus)).andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    /**
     * 构造已封存调账批次：行程 A->F 500 分，退款 100 分（L1=37、L2=63）纳入并封存。
     *
     * @param bizDate 行程与清分业务日期（同测试内多个批次须用不同日期）
     */
    private void sealedAdjustmentBatch(String prefix, String txnNo, String cardId, String batchNo,
                                       String bizDate) throws Exception {
        postJson("/api/metro/entry",
                ("{\"requestId\":\"%s-e\",\"txnNo\":\"%s\",\"cardId\":\"%s\",\"station\":\"A\","
                        + "\"entryTime\":\"%sT09:00:00\"}").formatted(prefix, txnNo, cardId, bizDate),
                200);
        postJson("/api/metro/exit",
                ("{\"requestId\":\"%s-x\",\"txnNo\":\"%s\",\"station\":\"F\","
                        + "\"exitTime\":\"%sT09:40:00\"}").formatted(prefix, txnNo, bizDate), 200);
        postJson("/api/metro/clearing/seal",
                "{\"requestId\":\"%s-s\",\"bizDate\":\"%s\"}".formatted(prefix, bizDate), 200);
        postJson("/api/metro/refunds/apply",
                ("{\"requestId\":\"%s-ra\",\"refundNo\":\"R-%s\",\"txnNo\":\"%s\",\"amountCents\":100,"
                        + "\"applyTime\":\"2026-09-26T10:00:00\"}").formatted(prefix, prefix, txnNo), 200);
        postJson("/api/metro/refunds/confirm",
                ("{\"requestId\":\"%s-rc1\",\"refundNo\":\"R-%s\",\"reviewer\":\"U1\","
                        + "\"reviewTime\":\"2026-09-26T11:00:00\",\"expectedVersion\":1}")
                        .formatted(prefix, prefix), 200);
        postJson("/api/metro/refunds/confirm",
                ("{\"requestId\":\"%s-rc2\",\"refundNo\":\"R-%s\",\"reviewer\":\"U2\","
                        + "\"reviewTime\":\"2026-09-26T11:05:00\",\"expectedVersion\":2}")
                        .formatted(prefix, prefix), 200);
        postJson("/api/metro/refunds/batches/open",
                ("{\"requestId\":\"%s-ro\",\"batchNo\":\"%s\",\"openTime\":\"2026-09-26T12:00:00\"}")
                        .formatted(prefix, batchNo), 200);
        postJson("/api/metro/refunds/batches/include",
                ("{\"requestId\":\"%s-ri\",\"adjustmentNo\":\"ADJ-R-%s\",\"batchNo\":\"%s\","
                        + "\"includeTime\":\"2026-09-26T12:05:00\",\"expectedVersion\":1}")
                        .formatted(prefix, prefix, batchNo), 200);
        JsonNode sealed = postJson("/api/metro/refunds/batches/seal",
                ("{\"requestId\":\"%s-rs\",\"batchNo\":\"%s\",\"sealTime\":\"2026-09-26T13:00:00\","
                        + "\"expectedVersion\":2}").formatted(prefix, batchNo), 200);
        assertEquals(-100L, sealed.get("totalCents").asLong());
    }

    private String openStatementBody(String requestId, String statementNo, String batchNo) {
        return ("{\"requestId\":\"%s\",\"statementNo\":\"%s\",\"batchNo\":\"%s\","
                + "\"openTime\":\"2026-09-27T09:00:00\"}").formatted(requestId, statementNo, batchNo);
    }

    private String receiptBody(String requestId, String statementNo, String batchNo, String flowNo,
                               String arrivedAt, long totalCents, int expectedVersion,
                               String allocationsJson) {
        return ("{\"requestId\":\"%s\",\"statementNo\":\"%s\",\"batchNo\":\"%s\","
                + "\"bankFlowNo\":\"%s\",\"arrivedAt\":\"%s\",\"totalCents\":%d,"
                + "\"expectedVersion\":%d,\"allocations\":%s}")
                .formatted(requestId, statementNo, batchNo, flowNo, arrivedAt, totalCents,
                        expectedVersion, allocationsJson);
    }

    @Test
    void openStatementFreezesDuePerLineAndSourceDetails() throws Exception {
        sealedAdjustmentBatch("m1", "TM1", "CARDM1", "ABM1", "2026-09-25");
        JsonNode opened = postJson("/api/metro/remittances/statements/open",
                openStatementBody("mo1", "SM1", "ABM1"), 200);
        assertEquals("OPEN", opened.get("status").asText());
        assertEquals(100L, opened.get("dueTotalCents").asLong());
        assertEquals(2, opened.get("lineCount").asInt());
        assertEquals(1, opened.get("version").asInt());
        assertFalse(opened.get("replayed").asBoolean());

        // 同键同参重放冻结响应
        JsonNode replay = postJson("/api/metro/remittances/statements/open",
                openStatementBody("mo1", "SM1", "ABM1"), 200);
        assertTrue(replay.get("replayed").asBoolean());
        assertEquals(100L, replay.get("dueTotalCents").asLong());
        // 同键异参 409
        assertEquals("IDEMPOTENCY_CONFLICT", postJson("/api/metro/remittances/statements/open",
                openStatementBody("mo1", "SM1X", "ABM1"), 409).get("code").asText());

        // 查询：应缴/已缴/余额，应缴可由冻结来源明细复算
        JsonNode view = getJson("/api/metro/remittances/statements/SM1", 200);
        assertEquals(100L, view.get("dueTotalCents").asLong());
        assertEquals(0L, view.get("paidTotalCents").asLong());
        assertEquals(100L, view.get("balanceCents").asLong());
        assertEquals(0, view.get("receipts").size());
        for (JsonNode line : view.get("lines")) {
            if ("L1".equals(line.get("lineCode").asText())) {
                assertEquals(37L, line.get("dueCents").asLong());
                assertEquals(37L, line.get("balanceCents").asLong());
            } else {
                assertEquals("L2", line.get("lineCode").asText());
                assertEquals(63L, line.get("dueCents").asLong());
            }
        }
        Long recomputed = jdbcTemplate.queryForObject(
                "SELECT COALESCE(SUM(-amount_cents),0) FROM remittance_source_detail s"
                        + " JOIN remittance_statement st ON st.id = s.statement_id"
                        + " WHERE st.statement_no = 'SM1'", Long.class);
        assertEquals(100L, recomputed, "应缴额可由冻结来源明细复算");
        Integer frozenDetails = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM remittance_source_detail s"
                        + " JOIN remittance_statement st ON st.id = s.statement_id"
                        + " WHERE st.statement_no = 'SM1'", Integer.class);
        assertEquals(2, frozenDetails, "全部来源明细已冻结");

        // 原调账批次快照与原清分批次不被覆盖
        JsonNode adjBatch = getJson("/api/metro/refunds/batches/ABM1", 200);
        assertEquals("SEALED", adjBatch.get("status").asText());
        assertEquals(-100L, adjBatch.get("totalCents").asLong());
        JsonNode clearing = getJson("/api/metro/clearing/batches/2026-09-25", 200);
        assertEquals(500L, clearing.get("totalCents").asLong());
    }

    @Test
    void onlyNonEmptySealedBatchCanOpenAndOneStatementPerBatch() throws Exception {
        sealedAdjustmentBatch("m2", "TM2", "CARDM2", "ABM2", "2026-09-25");
        // 未封存批次：拒绝
        postJson("/api/metro/refunds/batches/open",
                "{\"requestId\":\"m2-ro2\",\"batchNo\":\"ABM2O\",\"openTime\":\"2026-09-26T12:00:00\"}",
                200);
        assertEquals("REMIT_BATCH_NOT_SEALED", postJson("/api/metro/remittances/statements/open",
                openStatementBody("mo2a", "SM2A", "ABM2O"), 422).get("code").asText());
        // 空批次（封存但无明细）：拒绝
        postJson("/api/metro/refunds/batches/seal",
                "{\"requestId\":\"m2-rs2\",\"batchNo\":\"ABM2O\",\"sealTime\":\"2026-09-26T13:00:00\","
                        + "\"expectedVersion\":1}", 200);
        assertEquals("REMIT_BATCH_EMPTY", postJson("/api/metro/remittances/statements/open",
                openStatementBody("mo2b", "SM2B", "ABM2O"), 422).get("code").asText());
        // 批次不存在：404
        assertEquals("ADJ_BATCH_NOT_FOUND", postJson("/api/metro/remittances/statements/open",
                openStatementBody("mo2c", "SM2C", "NO-BATCH"), 404).get("code").asText());

        // 每批最多一单
        postJson("/api/metro/remittances/statements/open", openStatementBody("mo2d", "SM2", "ABM2"),
                200);
        assertEquals("REMIT_STATEMENT_EXISTS", postJson("/api/metro/remittances/statements/open",
                openStatementBody("mo2e", "SM2-SECOND", "ABM2"), 409).get("code").asText());
        // 对账单号全局唯一
        sealedAdjustmentBatch("m2b", "TM2B", "CARDM2B", "ABM2B", "2026-09-26");
        assertEquals("DUPLICATE_STATEMENT_NO", postJson("/api/metro/remittances/statements/open",
                openStatementBody("mo2f", "SM2", "ABM2B"), 409).get("code").asText());
    }

    @Test
    void partialThenFullReceiptsCompleteStatementAndViewShowsChain() throws Exception {
        sealedAdjustmentBatch("m3", "TM3", "CARDM3", "ABM3", "2026-09-25");
        postJson("/api/metro/remittances/statements/open", openStatementBody("mo3", "SM3", "ABM3"),
                200);

        // 第一笔：L1 部分缴 20（乱序到账：到账时刻晚于第二笔）
        JsonNode first = postJson("/api/metro/remittances/receipts",
                receiptBody("mr3a", "SM3", "ABM3", "FLOW-M3-1", "2026-09-27T03:00:00Z", 20, 1,
                        "[{\"lineCode\":\"L1\",\"amountCents\":20}]"), 200);
        assertEquals("OPEN", first.get("statementStatus").asText());
        assertEquals(20L, first.get("paidTotalCents").asLong());
        assertEquals(80L, first.get("balanceCents").asLong());
        assertEquals(2, first.get("version").asInt());
        assertFalse(first.get("replayed").asBoolean());

        // 第二笔：L1 余 17 + L2 全部 63，恰缴齐（到账时刻早于第一笔，乱序合法）
        JsonNode second = postJson("/api/metro/remittances/receipts",
                receiptBody("mr3b", "SM3", "ABM3", "FLOW-M3-2", "2026-09-27T01:00:00Z", 80, 2,
                        "[{\"lineCode\":\"L2\",\"amountCents\":63},"
                                + "{\"lineCode\":\"L1\",\"amountCents\":17}]"), 200);
        assertEquals("COMPLETED", second.get("statementStatus").asText());
        assertEquals(100L, second.get("paidTotalCents").asLong());
        assertEquals(0L, second.get("balanceCents").asLong());
        assertEquals(3, second.get("version").asInt());

        // 查询：应缴/已缴/余额与流水分配链
        JsonNode view = getJson("/api/metro/remittances/statements/SM3", 200);
        assertEquals("COMPLETED", view.get("status").asText());
        assertEquals(100L, view.get("paidTotalCents").asLong());
        assertEquals(0L, view.get("balanceCents").asLong());
        assertEquals(3, view.get("version").asInt());
        assertEquals(2, view.get("receipts").size());
        JsonNode receipt1 = view.get("receipts").get(0);
        assertEquals("FLOW-M3-1", receipt1.get("bankFlowNo").asText());
        assertEquals("2026-09-27T03:00:00Z", receipt1.get("arrivedAt").asText());
        assertEquals(20L, receipt1.get("totalCents").asLong());
        assertEquals(1, receipt1.get("allocations").size());
        JsonNode receipt2 = view.get("receipts").get(1);
        assertEquals(2, receipt2.get("allocations").size());
        assertEquals("L1", receipt2.get("allocations").get(0).get("lineCode").asText());
        assertEquals(17L, receipt2.get("allocations").get(0).get("amountCents").asLong());
        assertEquals("L2", receipt2.get("allocations").get(1).get("lineCode").asText());
        assertEquals(63L, receipt2.get("allocations").get(1).get("amountCents").asLong());
        for (JsonNode line : view.get("lines")) {
            assertEquals(0L, line.get("balanceCents").asLong());
        }

        // 完成后新流水拒绝
        assertEquals("REMIT_STATEMENT_COMPLETED", postJson("/api/metro/remittances/receipts",
                receiptBody("mr3c", "SM3", "ABM3", "FLOW-M3-3", "2026-09-27T04:00:00Z", 1, 3,
                        "[{\"lineCode\":\"L1\",\"amountCents\":1}]"), 422).get("code").asText());
    }

    @Test
    void mismatchByOneCentAndOverpayAreZeroPostedAtomically() throws Exception {
        sealedAdjustmentBatch("m4", "TM4", "CARDM4", "ABM4", "2026-09-25");
        postJson("/api/metro/remittances/statements/open", openStatementBody("mo4", "SM4", "ABM4"),
                200);

        // 差一分：分配合计 99 ≠ 总额 100，整笔零入账
        assertEquals("REMIT_TOTAL_MISMATCH", postJson("/api/metro/remittances/receipts",
                receiptBody("mr4a", "SM4", "ABM4", "FLOW-M4-1", "2026-09-27T01:00:00Z", 100, 1,
                        "[{\"lineCode\":\"L1\",\"amountCents\":37},"
                                + "{\"lineCode\":\"L2\",\"amountCents\":62}]"), 422)
                .get("code").asText());
        // 超缴：L1 分配 38 超过应缴 37，整笔零入账
        assertEquals("REMIT_OVERPAY", postJson("/api/metro/remittances/receipts",
                receiptBody("mr4b", "SM4", "ABM4", "FLOW-M4-2", "2026-09-27T01:00:00Z", 100, 1,
                        "[{\"lineCode\":\"L1\",\"amountCents\":38},"
                                + "{\"lineCode\":\"L2\",\"amountCents\":62}]"), 422)
                .get("code").asText());
        // 零入账验证：无凭据、无分配、已缴与版本不变
        assertEquals(0, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM remittance_receipt", Integer.class));
        assertEquals(0, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM remittance_receipt_line", Integer.class));
        JsonNode view = getJson("/api/metro/remittances/statements/SM4", 200);
        assertEquals(0L, view.get("paidTotalCents").asLong());
        assertEquals(1, view.get("version").asInt());

        // 失败不占键：修正金额后同 requestId 成功
        JsonNode fixed = postJson("/api/metro/remittances/receipts",
                receiptBody("mr4a", "SM4", "ABM4", "FLOW-M4-1", "2026-09-27T01:00:00Z", 100, 1,
                        "[{\"lineCode\":\"L1\",\"amountCents\":37},"
                                + "{\"lineCode\":\"L2\",\"amountCents\":63}]"), 200);
        assertFalse(fixed.get("replayed").asBoolean());
        assertEquals("COMPLETED", fixed.get("statementStatus").asText());
    }

    @Test
    void wrongBatchUnknownLineAndDuplicateLineRejected() throws Exception {
        sealedAdjustmentBatch("m5", "TM5", "CARDM5", "ABM5", "2026-09-25");
        sealedAdjustmentBatch("m5b", "TM5B", "CARDM5B", "ABM5B", "2026-09-26");
        postJson("/api/metro/remittances/statements/open", openStatementBody("mo5", "SM5", "ABM5"),
                200);

        // 错批：回执批次与对账单来源批次不一致
        assertEquals("REMIT_BATCH_MISMATCH", postJson("/api/metro/remittances/receipts",
                receiptBody("mr5a", "SM5", "ABM5B", "FLOW-M5-1", "2026-09-27T01:00:00Z", 10, 1,
                        "[{\"lineCode\":\"L1\",\"amountCents\":10}]"), 422).get("code").asText());
        // 线路不属于本对账单
        assertEquals("REMIT_LINE_UNKNOWN", postJson("/api/metro/remittances/receipts",
                receiptBody("mr5b", "SM5", "ABM5", "FLOW-M5-2", "2026-09-27T01:00:00Z", 10, 1,
                        "[{\"lineCode\":\"L9\",\"amountCents\":10}]"), 422).get("code").asText());
        // 同一回执内线路重复
        assertEquals("REMIT_LINE_DUPLICATE", postJson("/api/metro/remittances/receipts",
                receiptBody("mr5c", "SM5", "ABM5", "FLOW-M5-3", "2026-09-27T01:00:00Z", 20, 1,
                        "[{\"lineCode\":\"L1\",\"amountCents\":10},"
                                + "{\"lineCode\":\"L1\",\"amountCents\":10}]"), 422)
                .get("code").asText());
        // 参数校验：空分配、超 10 条、非正总额
        assertEquals("VALIDATION_FAILED", postJson("/api/metro/remittances/receipts",
                receiptBody("mr5d", "SM5", "ABM5", "FLOW-M5-4", "2026-09-27T01:00:00Z", 0, 1, "[]"),
                400).get("code").asText());
        StringBuilder eleven = new StringBuilder("[");
        for (int i = 0; i < 11; i++) {
            eleven.append(i == 0 ? "" : ",")
                    .append("{\"lineCode\":\"L").append(i).append("\",\"amountCents\":1}");
        }
        assertEquals("VALIDATION_FAILED", postJson("/api/metro/remittances/receipts",
                receiptBody("mr5e", "SM5", "ABM5", "FLOW-M5-5", "2026-09-27T01:00:00Z", 11, 1,
                        eleven + "]"), 400).get("code").asText());
        // 全部失败零入账
        assertEquals(0, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM remittance_receipt", Integer.class));
    }

    @Test
    void bankFlowReplayBindsNewRequestIdAndDifferentContentConflicts() throws Exception {
        sealedAdjustmentBatch("m6", "TM6", "CARDM6", "ABM6", "2026-09-25");
        postJson("/api/metro/remittances/statements/open", openStatementBody("mo6", "SM6", "ABM6"),
                200);
        String body = receiptBody("mr6a", "SM6", "ABM6", "FLOW-M6-1", "2026-09-27T01:00:00Z", 20, 1,
                "[{\"lineCode\":\"L1\",\"amountCents\":20}]");

        JsonNode first = postJson("/api/metro/remittances/receipts", body, 200);
        assertFalse(first.get("replayed").asBoolean());
        // 同 requestId 同参：重放
        assertTrue(postJson("/api/metro/remittances/receipts", body, 200).get("replayed")
                .asBoolean());
        // 同流水同完整内容、新 requestId：返回首次结果并绑定新 requestId
        String replayBody = receiptBody("mr6b", "SM6", "ABM6", "FLOW-M6-1", "2026-09-27T01:00:00Z",
                20, 1, "[{\"lineCode\":\"L1\",\"amountCents\":20}]");
        JsonNode flowReplay = postJson("/api/metro/remittances/receipts", replayBody, 200);
        assertTrue(flowReplay.get("replayed").asBoolean());
        assertEquals(2, flowReplay.get("version").asInt());
        // 新 requestId 已绑定：再次同参仍为重放
        assertTrue(postJson("/api/metro/remittances/receipts", replayBody, 200).get("replayed")
                .asBoolean());
        // 仅入账一次
        assertEquals(1, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM remittance_receipt", Integer.class));
        assertEquals(20L, getJson("/api/metro/remittances/statements/SM6", 200)
                .get("paidTotalCents").asLong());

        // 同流水改金额：409
        assertEquals("BANK_FLOW_CONFLICT", postJson("/api/metro/remittances/receipts",
                receiptBody("mr6c", "SM6", "ABM6", "FLOW-M6-1", "2026-09-27T01:00:00Z", 21, 1,
                        "[{\"lineCode\":\"L1\",\"amountCents\":21}]"), 409).get("code").asText());
        // 同流水改时刻：409
        assertEquals("BANK_FLOW_CONFLICT", postJson("/api/metro/remittances/receipts",
                receiptBody("mr6d", "SM6", "ABM6", "FLOW-M6-1", "2026-09-27T02:00:00Z", 20, 1,
                        "[{\"lineCode\":\"L1\",\"amountCents\":20}]"), 409).get("code").asText());
        // 同流水换批：409
        assertEquals("BANK_FLOW_CONFLICT", postJson("/api/metro/remittances/receipts",
                receiptBody("mr6e", "SM6", "ABM6X", "FLOW-M6-1", "2026-09-27T01:00:00Z", 20, 1,
                        "[{\"lineCode\":\"L1\",\"amountCents\":20}]"), 409).get("code").asText());
        // 同 requestId 异参：409
        assertEquals("IDEMPOTENCY_CONFLICT", postJson("/api/metro/remittances/receipts",
                receiptBody("mr6a", "SM6", "ABM6", "FLOW-M6-9", "2026-09-27T01:00:00Z", 20, 1,
                        "[{\"lineCode\":\"L1\",\"amountCents\":20}]"), 409).get("code").asText());
    }

    @Test
    void expectedVersionGuardsReceipts() throws Exception {
        sealedAdjustmentBatch("m7", "TM7", "CARDM7", "ABM7", "2026-09-25");
        postJson("/api/metro/remittances/statements/open", openStatementBody("mo7", "SM7", "ABM7"),
                200);
        // 旧版本 409
        assertEquals("VERSION_CONFLICT", postJson("/api/metro/remittances/receipts",
                receiptBody("mr7a", "SM7", "ABM7", "FLOW-M7-1", "2026-09-27T01:00:00Z", 20, 5,
                        "[{\"lineCode\":\"L1\",\"amountCents\":20}]"), 409).get("code").asText());
        // 正确版本成功，版本 1 -> 2
        JsonNode ok = postJson("/api/metro/remittances/receipts",
                receiptBody("mr7b", "SM7", "ABM7", "FLOW-M7-2", "2026-09-27T01:00:00Z", 20, 1,
                        "[{\"lineCode\":\"L1\",\"amountCents\":20}]"), 200);
        assertEquals(2, ok.get("version").asInt());
        // 上一笔的旧版本再次使用：409
        assertEquals("VERSION_CONFLICT", postJson("/api/metro/remittances/receipts",
                receiptBody("mr7c", "SM7", "ABM7", "FLOW-M7-3", "2026-09-27T02:00:00Z", 10, 1,
                        "[{\"lineCode\":\"L2\",\"amountCents\":10}]"), 409).get("code").asText());
    }

    @Test
    void closingCardDoesNotBlockRemittanceOrReplay() throws Exception {
        sealedAdjustmentBatch("m8", "TM8", "CARDM8", "ABM8", "2026-09-25");
        postJson("/api/metro/remittances/statements/open", openStatementBody("mo8", "SM8", "ABM8"),
                200);
        // 关闭原票卡
        postJson("/api/metro/refunds/cards/close",
                "{\"requestId\":\"m8-rk\",\"cardId\":\"CARDM8\",\"closeTime\":\"2026-09-27T08:00:00\","
                        + "\"expectedVersion\":1}", 200);
        // 关卡不妨碍合法缴回
        JsonNode receipt = postJson("/api/metro/remittances/receipts",
                receiptBody("mr8a", "SM8", "ABM8", "FLOW-M8-1", "2026-09-27T09:00:00Z", 100, 1,
                        "[{\"lineCode\":\"L1\",\"amountCents\":37},"
                                + "{\"lineCode\":\"L2\",\"amountCents\":63}]"), 200);
        assertEquals("COMPLETED", receipt.get("statementStatus").asText());
        // 成功重放不受关卡影响
        assertTrue(postJson("/api/metro/remittances/receipts",
                receiptBody("mr8a", "SM8", "ABM8", "FLOW-M8-1", "2026-09-27T09:00:00Z", 100, 1,
                        "[{\"lineCode\":\"L1\",\"amountCents\":37},"
                                + "{\"lineCode\":\"L2\",\"amountCents\":63}]"), 200)
                .get("replayed").asBoolean());
        // 关卡不重新开放退款额度
        JsonNode balance = getJson("/api/metro/refunds/balance/TM8", 200);
        assertEquals(100L, balance.get("refundedCents").asLong());
        assertEquals(400L, balance.get("availableCents").asLong());
    }

    @Test
    void missingStatementReturns404() throws Exception {
        assertEquals("REMIT_STATEMENT_NOT_FOUND",
                getJson("/api/metro/remittances/statements/NO-SUCH", 404).get("code").asText());
        assertEquals("REMIT_STATEMENT_NOT_FOUND", postJson("/api/metro/remittances/receipts",
                receiptBody("mr9a", "NO-SUCH", "ABM9", "FLOW-M9-1", "2026-09-27T01:00:00Z", 10, 1,
                        "[{\"lineCode\":\"L1\",\"amountCents\":10}]"), 404).get("code").asText());
    }
}
