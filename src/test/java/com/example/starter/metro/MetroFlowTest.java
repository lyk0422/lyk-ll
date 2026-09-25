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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 轨交清分 API 主流程与业务边界测试（H2 内存库，MySQL 兼容模式）。
 * 合成线网：L1 A-B 3km / B-C 3km / C-D 4km；L2 C-E 5km / E-F 5km；L3 D-G 6km / G-H 4km。
 */
@SpringBootTest
@AutoConfigureMockMvc
class MetroFlowTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @BeforeEach
    void cleanDatabase() {
        jdbcTemplate.update("DELETE FROM clearing_detail");
        jdbcTemplate.update("DELETE FROM clearing_discrepancy");
        jdbcTemplate.update("DELETE FROM metro_transaction");
        jdbcTemplate.update("DELETE FROM clearing_batch");
        jdbcTemplate.update("DELETE FROM idempotency_record");
    }

    private JsonNode postEntry(String requestId, String txnNo, String cardId, String station,
                               String entryTime, int expectedStatus) throws Exception {
        String body = """
                {"requestId":"%s","txnNo":"%s","cardId":"%s","station":"%s","entryTime":"%s"}
                """.formatted(requestId, txnNo, cardId, station, entryTime);
        MvcResult result = mockMvc.perform(post("/api/metro/entry")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().is(expectedStatus)).andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private JsonNode postExit(String requestId, String txnNo, String station,
                              String exitTime, int expectedStatus) throws Exception {
        String body = """
                {"requestId":"%s","txnNo":"%s","station":"%s","exitTime":"%s"}
                """.formatted(requestId, txnNo, station, exitTime);
        MvcResult result = mockMvc.perform(post("/api/metro/exit")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().is(expectedStatus)).andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private JsonNode postEstimate(String requestId, String txnNo, int expectedStatus) throws Exception {
        String body = """
                {"requestId":"%s","txnNo":"%s"}
                """.formatted(requestId, txnNo);
        MvcResult result = mockMvc.perform(post("/api/metro/estimate")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().is(expectedStatus)).andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private JsonNode postSeal(String requestId, String bizDate, int expectedStatus) throws Exception {
        String body = """
                {"requestId":"%s","bizDate":"%s"}
                """.formatted(requestId, bizDate);
        MvcResult result = mockMvc.perform(post("/api/metro/clearing/seal")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().is(expectedStatus)).andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private JsonNode getBatch(String bizDate) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/metro/clearing/batches/" + bizDate))
                .andExpect(status().isOk()).andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    @Test
    void entryThenExitChargesShortestPathFare() throws Exception {
        JsonNode entry = postEntry("r-e1", "T-E1", "CARD1", "A", "2026-09-25T09:00:00", 200);
        assertEquals("ENTERED", entry.get("status").asText());

        // A->D 最短路径 10 公里：2 + ceil(6/5) = 4 元
        JsonNode exit = postExit("r-x1", "T-E1", "D", "2026-09-25T09:30:00", 200);
        assertEquals("COMPLETED", exit.get("status").asText());
        assertEquals(10000L, exit.get("distanceM").asLong());
        assertEquals(400L, exit.get("fareCents").asLong());
        assertEquals(0L, exit.get("penaltyCents").asLong());
        assertTrue(exit.get("lateDiscrepancy").asBoolean() == false);

        JsonNode batch = getBatch("2026-09-25");
        assertEquals(400L, batch.get("totalCents").asLong());
        assertEquals(1, batch.get("txnCount").asInt());
    }

    @Test
    void exactFiveKilometerStepIsCharged() throws Exception {
        postEntry("r-e2", "T-E2", "CARD2", "E", "2026-09-25T09:00:00", 200);
        // E->D = 5+4 = 9 公里，恰满 5 公里加价档：2 + 1 = 3 元
        JsonNode exit = postExit("r-x2", "T-E2", "D", "2026-09-25T09:20:00", 200);
        assertEquals(9000L, exit.get("distanceM").asLong());
        assertEquals(300L, exit.get("fareCents").asLong());
    }

    @Test
    void transferWithinThirtyMinutesChargesSingleBaseFare() throws Exception {
        // 第一程 C->D 4 公里，10:00 出站
        postEntry("r-e3a", "T-E3A", "CARD3", "C", "2026-09-25T09:40:00", 200);
        JsonNode first = postExit("r-x3a", "T-E3A", "D", "2026-09-25T10:00:00", 200);
        assertEquals(200L, first.get("fareCents").asLong());

        // 恰 30 分钟后换乘进站，D->G 6 公里：累计 10 公里只计一次起步价，应收 4-2=2 元
        postEntry("r-e3b", "T-E3B", "CARD3", "D", "2026-09-25T10:30:00", 200);
        JsonNode second = postExit("r-x3b", "T-E3B", "G", "2026-09-25T10:40:00", 200);
        assertTrue(second.get("transfer").asBoolean());
        assertEquals(200L, second.get("fareCents").asLong());
    }

    @Test
    void transferAfterThirtyOneMinutesStartsNewChain() throws Exception {
        postEntry("r-e4a", "T-E4A", "CARD4", "C", "2026-09-25T09:40:00", 200);
        postExit("r-x4a", "T-E4A", "D", "2026-09-25T10:00:00", 200);

        // 31 分钟后进站，不计换乘：D->G 6 公里独立计价 3 元
        postEntry("r-e4b", "T-E4B", "CARD4", "D", "2026-09-25T10:31:00", 200);
        JsonNode second = postExit("r-x4b", "T-E4B", "G", "2026-09-25T10:40:00", 200);
        assertTrue(!second.get("transfer").asBoolean());
        assertEquals(300L, second.get("fareCents").asLong());
    }

    @Test
    void exactFourHoursIsNotPenalized() throws Exception {
        postEntry("r-e5", "T-E5", "CARD5", "C", "2026-09-25T08:00:00", 200);
        JsonNode exit = postExit("r-x5", "T-E5", "D", "2026-09-25T12:00:00", 200);
        assertEquals(0L, exit.get("penaltyCents").asLong());
        assertEquals(200L, exit.get("fareCents").asLong());
    }

    @Test
    void overFourHoursIsChargedNetworkMaxFare() throws Exception {
        postEntry("r-e6", "T-E6", "CARD6", "C", "2026-09-25T08:00:00", 200);
        // 4 小时 1 分钟：按线网最高票价 6 元补收（24 公里 -> 6 元）
        JsonNode exit = postExit("r-x6", "T-E6", "D", "2026-09-25T12:01:00", 200);
        assertEquals(400L, exit.get("penaltyCents").asLong());
        assertEquals(600L, exit.get("fareCents").asLong());
    }

    @Test
    void crossLineAllocationRoundsAndAssignsRemainderToLongestLine() throws Exception {
        postEntry("r-e7", "T-E7", "CARD7", "A", "2026-09-25T09:00:00", 200);
        // A->F 16 公里 5 元：L1 6km 比例 0.3750 -> 187.5 取整 188；L2 10km 比例 0.6250 -> 差额 312
        JsonNode exit = postExit("r-x7", "T-E7", "F", "2026-09-25T09:40:00", 200);
        assertEquals(16000L, exit.get("distanceM").asLong());
        assertEquals(500L, exit.get("fareCents").asLong());

        JsonNode batch = getBatch("2026-09-25");
        JsonNode details = batch.get("details");
        assertEquals(2, details.size());
        long sum = 0;
        for (JsonNode detail : details) {
            sum += detail.get("amountCents").asLong();
            if ("L1".equals(detail.get("lineCode").asText())) {
                assertEquals("0.3750", detail.get("ratio").asText());
                assertEquals(188L, detail.get("amountCents").asLong());
            } else {
                assertEquals("L2", detail.get("lineCode").asText());
                assertEquals("0.6250", detail.get("ratio").asText());
                // 差额归入里程最长线路 L2（直接取整会得到 313）
                assertEquals(312L, detail.get("amountCents").asLong());
            }
        }
        assertEquals(500L, sum);
        assertEquals(500L, batch.get("totalCents").asLong());
    }

    @Test
    void duplicateTxnNoIsRejected() throws Exception {
        postEntry("r-e8a", "T-E8", "CARD8", "A", "2026-09-25T09:00:00", 200);
        JsonNode conflict = postEntry("r-e8b", "T-E8", "CARD8", "B", "2026-09-25T09:05:00", 409);
        assertEquals("DUPLICATE_TXN_NO", conflict.get("code").asText());
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM metro_transaction WHERE txn_no = 'T-E8'", Integer.class);
        assertEquals(1, count);
    }

    @Test
    void sameKeyReplayReturnsFirstResultAndDifferentParamsReturn409() throws Exception {
        JsonNode first = postEntry("r-e9", "T-E9", "CARD9", "A", "2026-09-25T09:00:00", 200);
        assertTrue(!first.get("replayed").asBoolean());

        // 同键同参：返回首次结果
        JsonNode replay = postEntry("r-e9", "T-E9", "CARD9", "A", "2026-09-25T09:00:00", 200);
        assertTrue(replay.get("replayed").asBoolean());
        assertEquals("T-E9", replay.get("txnNo").asText());

        // 同键异参：409 且不重复入账
        JsonNode conflict = postEntry("r-e9", "T-E9", "CARD9", "B", "2026-09-25T09:00:00", 409);
        assertEquals("IDEMPOTENCY_CONFLICT", conflict.get("code").asText());
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM metro_transaction WHERE txn_no = 'T-E9'", Integer.class);
        assertEquals(1, count);
    }

    @Test
    void estimateThenCompensateReversesAtomicallyWithoutDoubleCounting() throws Exception {
        postEntry("r-e10", "T-E10", "CARD10", "A", "2026-09-25T09:00:00", 200);
        // 出闸缺失：按 A 站最远站票价暂记（A->H 20 公里 -> 6 元）
        JsonNode estimate = postEstimate("r-m10", "T-E10", 200);
        assertEquals("ESTIMATED", estimate.get("status").asText());
        assertEquals(600L, estimate.get("fareCents").asLong());
        assertEquals(600L, getBatch("2026-09-25").get("totalCents").asLong());

        // 补登出闸：同一事务内冲销暂记并按实结算（A->D 10 公里 4 元）
        JsonNode exit = postExit("r-x10", "T-E10", "D", "2026-09-25T09:20:00", 200);
        assertTrue(exit.get("compensated").asBoolean());
        assertEquals(400L, exit.get("fareCents").asLong());

        JsonNode batch = getBatch("2026-09-25");
        // 不得双边计额：600 - 600 + 400 = 400
        assertEquals(400L, batch.get("totalCents").asLong());
        long estimateSum = 0;
        long reversalSum = 0;
        long settleSum = 0;
        for (JsonNode detail : batch.get("details")) {
            switch (detail.get("entryType").asText()) {
                case "ESTIMATE" -> estimateSum += detail.get("amountCents").asLong();
                case "REVERSAL" -> reversalSum += detail.get("amountCents").asLong();
                case "SETTLE" -> settleSum += detail.get("amountCents").asLong();
                default -> throw new IllegalStateException("unexpected type");
            }
        }
        assertEquals(600L, estimateSum);
        assertEquals(-600L, reversalSum);
        assertEquals(400L, settleSum);
    }

    @Test
    void sealIsTerminalAndLateTransactionsGoToDiscrepancy() throws Exception {
        postEntry("r-e11a", "T-E11A", "CARD11", "A", "2026-09-25T09:00:00", 200);
        postExit("r-x11a", "T-E11A", "D", "2026-09-25T09:30:00", 200);

        JsonNode seal = postSeal("r-s11", "2026-09-25", 200);
        assertEquals("SEALED", seal.get("status").asText());
        assertEquals(400L, seal.get("totalCents").asLong());
        assertTrue(!seal.get("replayed").asBoolean());

        // 重复封账（同键重放与新键）均幂等成功，合计不变
        JsonNode resealSameKey = postSeal("r-s11", "2026-09-25", 200);
        assertTrue(resealSameKey.get("replayed").asBoolean());
        assertEquals(400L, resealSameKey.get("totalCents").asLong());
        JsonNode resealNewKey = postSeal("r-s11b", "2026-09-25", 200);
        assertEquals(400L, resealNewKey.get("totalCents").asLong());

        // 封账后迟到交易：登记差异，不改已封账合计
        postEntry("r-e11b", "T-E11B", "CARD11", "C", "2026-09-25T23:00:00", 200);
        JsonNode lateExit = postExit("r-x11b", "T-E11B", "D", "2026-09-25T23:30:00", 200);
        assertTrue(lateExit.get("lateDiscrepancy").asBoolean());

        JsonNode batch = getBatch("2026-09-25");
        assertEquals(400L, batch.get("totalCents").asLong());
        assertEquals(1, batch.get("txnCount").asInt());
        JsonNode discrepancies = batch.get("discrepancies");
        assertEquals(1, discrepancies.size());
        assertEquals("T-E11B", discrepancies.get(0).get("txnNo").asText());
        assertEquals(200L, discrepancies.get(0).get("amountCents").asLong());
    }

    @Test
    void exitOnUnknownTxnReturns404() throws Exception {
        JsonNode notFound = postExit("r-x12", "T-UNKNOWN", "D", "2026-09-25T09:30:00", 404);
        assertEquals("TXN_NOT_FOUND", notFound.get("code").asText());
    }

    @Test
    void exitBeforeEntryIsRejected() throws Exception {
        postEntry("r-e13", "T-E13", "CARD13", "A", "2026-09-25T09:00:00", 200);
        JsonNode error = postExit("r-x13", "T-E13", "D", "2026-09-25T08:00:00", 400);
        assertEquals("EXIT_BEFORE_ENTRY", error.get("code").asText());
    }
}
