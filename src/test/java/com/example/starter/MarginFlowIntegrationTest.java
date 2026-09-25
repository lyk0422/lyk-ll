package com.example.starter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 追保与强平主流程集成测试（H2 真实库）：
 * 130%/140%/110% 阈值口径、通知唯一合并、入账幂等、逾期强平、阶梯平仓即止。
 */
class MarginFlowIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private ObjectMapper om;

    private long createAccount(String accountNo) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/accounts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"accountNo\":\"" + accountNo + "\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        return om.readTree(result.getResponse().getContentAsString()).get("id").asLong();
    }

    private JsonNode snapshot(long accountId, String totalAssets, String... liabilities) throws Exception {
        StringBuilder body = new StringBuilder("{\"totalAssets\":\"" + totalAssets + "\",\"liabilities\":[");
        for (int i = 0; i < liabilities.length; i++) {
            if (i > 0) {
                body.append(',');
            }
            body.append('\"').append(liabilities[i]).append('\"');
        }
        body.append("]}");
        MvcResult result = mockMvc.perform(post("/api/accounts/{id}/snapshots", accountId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body.toString()))
                .andExpect(status().isCreated())
                .andReturn();
        return om.readTree(result.getResponse().getContentAsString());
    }

    private MvcResult depositRaw(long accountId, String requestId, String amount, String currency)
            throws Exception {
        return mockMvc.perform(post("/api/accounts/{id}/deposits", accountId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"" + requestId + "\",\"amount\":\"" + amount
                                + "\",\"currency\":\"" + currency + "\"}"))
                .andReturn();
    }

    private JsonNode accountView(long accountId) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/accounts/{id}", accountId))
                .andExpect(status().isOk())
                .andReturn();
        return om.readTree(result.getResponse().getContentAsString());
    }

    @Test
    void threshold_exactly130Percent_noAction() throws Exception {
        long accountId = createAccount("T130");
        JsonNode resp = snapshot(accountId, "130.00", "100.00");
        assertEquals("NONE", resp.get("action").asText());
        assertEquals(13000, resp.get("ratioBp").asInt());
        assertEquals(0, queryLong("SELECT COUNT(*) FROM margin_call WHERE account_id = ?", accountId));
    }

    @Test
    void threshold_below130_marginCallWithNextTradingDayDeadline() throws Exception {
        long accountId = createAccount("T129");
        JsonNode resp = snapshot(accountId, "129.99", "100.00");
        assertEquals("MARGIN_CALL", resp.get("action").asText());
        // 触发于周五，截止为次一交易日（周一）15:00
        JsonNode view = accountView(accountId);
        assertEquals("2026-09-28T15:00", view.get("openMarginCall").get("deadline").asText());
    }

    @Test
    void threshold_exactly110Percent_marginCallNotDirectLiquidation() throws Exception {
        long accountId = createAccount("T110");
        JsonNode resp = snapshot(accountId, "110.00", "100.00");
        assertEquals("MARGIN_CALL", resp.get("action").asText());
        assertEquals(0, queryLong("SELECT COUNT(*) FROM liquidation_order WHERE account_id = ?", accountId));
    }

    @Test
    void threshold_below110_directLiquidationSkipsMarginCall() throws Exception {
        long accountId = createAccount("T109");
        JsonNode resp = snapshot(accountId, "109.99", "100.00");
        assertEquals("DIRECT_LIQUIDATION", resp.get("action").asText());
        assertTrue(resp.get("liquidationOrderId").asLong() > 0);
        assertEquals(0, queryLong("SELECT COUNT(*) FROM margin_call WHERE account_id = ?", accountId));
        assertEquals("PENDING", jdbc.queryForObject(
                "SELECT status FROM liquidation_order WHERE account_id = ?", String.class, accountId));
    }

    @Test
    void marginCall_uniquePerCycle_repeatedTriggerMerged() throws Exception {
        long accountId = createAccount("MC1");
        JsonNode first = snapshot(accountId, "129.00", "100.00");
        JsonNode second = snapshot(accountId, "128.00", "100.00");
        assertEquals("MARGIN_CALL", first.get("action").asText());
        assertEquals("MARGIN_CALL", second.get("action").asText());
        long callId = first.get("marginCallId").asLong();
        assertEquals(callId, second.get("marginCallId").asLong());
        // 同一未结清周期仅一条通知，重复触发合并计数
        assertEquals(1, queryLong("SELECT COUNT(*) FROM margin_call WHERE account_id = ?", accountId));
        assertEquals(1, queryLong("SELECT merge_count FROM margin_call WHERE id = ?", callId));

        // 补足结清后再次低于 130%：进入新周期，生成新通知（第二次快照后资产 128.00，补 12.00 恰好 140%）
        depositRaw(accountId, "MC1-D1", "12.00", "CNY");
        assertEquals("RESOLVED", jdbc.queryForObject(
                "SELECT status FROM margin_call WHERE id = ?", String.class, callId));
        JsonNode third = snapshot(accountId, "129.00", "100.00");
        assertEquals("MARGIN_CALL", third.get("action").asText());
        assertTrue(third.get("marginCallId").asLong() != callId);
        assertEquals(2, queryLong("SELECT COUNT(*) FROM margin_call WHERE account_id = ?", accountId));
    }

    @Test
    void deposit_idempotent_sameKeySameParamsReturnsFirstResult() throws Exception {
        long accountId = createAccount("DP1");
        JsonNode call = snapshot(accountId, "129.00", "100.00");
        long callId = call.get("marginCallId").asLong();

        MvcResult first = depositRaw(accountId, "REQ-1", "11.00", "CNY");
        assertEquals(201, first.getResponse().getStatus());
        JsonNode firstBody = om.readTree(first.getResponse().getContentAsString());
        assertEquals(14000, firstBody.get("ratioBpAfter").asInt());
        assertEquals(callId, firstBody.get("resolvedMarginCallId").asLong());

        // 同键同参重放：返回首次结果，不重复入账
        MvcResult replay = depositRaw(accountId, "REQ-1", "11.00", "CNY");
        assertEquals(200, replay.getResponse().getStatus());
        JsonNode replayBody = om.readTree(replay.getResponse().getContentAsString());
        assertTrue(replayBody.get("duplicated").asBoolean());
        assertEquals(firstBody.get("depositId").asLong(), replayBody.get("depositId").asLong());
        assertEquals(14000, replayBody.get("ratioBpAfter").asInt());
        assertEquals(1, queryLong("SELECT COUNT(*) FROM deposit WHERE request_id = 'REQ-1'"));
        JsonNode view = accountView(accountId);
        assertEquals("140.00", view.get("totalAssets").asText());
        assertEquals("RESOLVED", jdbc.queryForObject(
                "SELECT status FROM margin_call WHERE id = ?", String.class, callId));
    }

    @Test
    void deposit_sameKeyDifferentParams_conflict409NoDoubleCredit() throws Exception {
        long accountId = createAccount("DP2");
        snapshot(accountId, "129.00", "100.00");
        assertEquals(201, depositRaw(accountId, "REQ-2", "11.00", "CNY").getResponse().getStatus());
        // 同键异参：409 且不重复入账
        assertEquals(409, depositRaw(accountId, "REQ-2", "12.00", "CNY").getResponse().getStatus());
        assertEquals(409, depositRaw(accountId, "REQ-2", "11.00", "USD").getResponse().getStatus());
        assertEquals(1, queryLong("SELECT COUNT(*) FROM deposit WHERE account_id = ?", accountId));
        assertEquals("140.00", accountView(accountId).get("totalAssets").asText());
    }

    @Test
    void deposit_invalidAmount_rejected400() throws Exception {
        long accountId = createAccount("DP3");
        snapshot(accountId, "129.00", "100.00");
        assertEquals(400, depositRaw(accountId, "REQ-3", "1.001", "CNY").getResponse().getStatus());
        assertEquals(400, depositRaw(accountId, "REQ-4", "-5.00", "CNY").getResponse().getStatus());
        assertEquals(400, depositRaw(accountId, "REQ-5", "0", "CNY").getResponse().getStatus());
        assertEquals(0, queryLong("SELECT COUNT(*) FROM deposit WHERE account_id = ?", accountId));
    }

    @Test
    void overdue_marginCallEscalatesToLiquidation_ladderStopsAt140() throws Exception {
        long accountId = createAccount("OV1");
        snapshot(accountId, "129.00", "100.00");
        // 部分补足 5.00：比例 134%，仍未达 140%，通知保持未结清
        depositRaw(accountId, "OV1-D1", "5.00", "CNY");
        assertEquals("OPEN", jdbc.queryForObject(
                "SELECT status FROM margin_call WHERE account_id = ?", String.class, accountId));

        // 截止前扫描：不产生强平指令
        MvcResult early = mockMvc.perform(post("/api/liquidations/process-due")
                        .param("asOf", "2026-09-28T14:59:00"))
                .andExpect(status().isOk()).andReturn();
        assertEquals(0, om.readTree(early.getResponse().getContentAsString())
                .get("createdOrderIds").size());

        // 逾期扫描：生成强平指令
        MvcResult due = mockMvc.perform(post("/api/liquidations/process-due")
                        .param("asOf", "2026-09-28T15:01:00"))
                .andExpect(status().isOk()).andReturn();
        JsonNode dueBody = om.readTree(due.getResponse().getContentAsString());
        assertEquals(1, dueBody.get("createdOrderIds").size());
        long orderId = dueBody.get("createdOrderIds").get(0).asLong();

        // 执行：A=134.00, L=100.00，需还 15.00 恰好回到 140%，即止不多平
        MvcResult exec = mockMvc.perform(post("/api/liquidations/{id}/execute", orderId))
                .andExpect(status().isOk()).andReturn();
        JsonNode execBody = om.readTree(exec.getResponse().getContentAsString());
        assertEquals("DONE", execBody.get("status").asText());
        assertEquals(1, execBody.get("items").size());
        assertEquals("15.00", execBody.get("items").get(0).get("amount").asText());
        assertEquals(14000, execBody.get("ratioBpAfter").asInt());

        JsonNode view = accountView(accountId);
        assertEquals("119.00", view.get("totalAssets").asText());
        assertEquals("85.00", view.get("totalLiabilities").asText());
        assertEquals("LIQUIDATED", jdbc.queryForObject(
                "SELECT status FROM margin_call WHERE account_id = ?", String.class, accountId));
        // 终态不可撤销：重复执行 409
        mockMvc.perform(post("/api/liquidations/{id}/execute", orderId))
                .andExpect(status().isConflict());
    }

    @Test
    void ladder_liquidatesLargestFirst_stopsExactlyAt140() throws Exception {
        long accountId = createAccount("LD1");
        // 比例 115%：追保；负债三笔 100/80/20
        JsonNode call = snapshot(accountId, "230.00", "100.00", "80.00", "20.00");
        assertEquals("MARGIN_CALL", call.get("action").asText());

        MvcResult due = mockMvc.perform(post("/api/liquidations/process-due")
                        .param("asOf", "2026-09-28T15:01:00"))
                .andExpect(status().isOk()).andReturn();
        long orderId = om.readTree(due.getResponse().getContentAsString())
                .get("createdOrderIds").get(0).asLong();

        // 需还 (140*20000-100*23000)/40 = 125.00：先平 100.00 整笔，再部分平 25.00，即止
        MvcResult exec = mockMvc.perform(post("/api/liquidations/{id}/execute", orderId))
                .andExpect(status().isOk()).andReturn();
        JsonNode items = om.readTree(exec.getResponse().getContentAsString()).get("items");
        assertEquals(2, items.size());
        assertEquals("100.00", items.get(0).get("amount").asText());
        assertEquals("25.00", items.get(1).get("amount").asText());

        JsonNode view = accountView(accountId);
        assertEquals("105.00", view.get("totalAssets").asText());
        assertEquals("75.00", view.get("totalLiabilities").asText());
        assertEquals(14000, view.get("ratioBp").asInt());
        // 不多平：最小一笔 20.00 未被触碰，80.00 一笔剩余 55.00
        assertEquals(1, queryLong("SELECT COUNT(*) FROM liability WHERE account_id = ?"
                + " AND outstanding = 2000 AND status = 'OPEN'", accountId));
        assertEquals(1, queryLong("SELECT COUNT(*) FROM liability WHERE account_id = ?"
                + " AND outstanding = 5500 AND status = 'OPEN'", accountId));
    }

    @Test
    void deposit_reaching140_cancelsPendingLiquidationOrder() throws Exception {
        long accountId = createAccount("RC1");
        // 低于 110% 直接生成待发强平指令
        JsonNode resp = snapshot(accountId, "109.99", "100.00");
        long orderId = resp.get("liquidationOrderId").asLong();

        // 入账使比例达标（恰好 140%）：撤销待发强平
        MvcResult deposit = depositRaw(accountId, "RC1-D1", "30.01", "CNY");
        assertEquals(201, deposit.getResponse().getStatus());
        JsonNode body = om.readTree(deposit.getResponse().getContentAsString());
        assertEquals(14000, body.get("ratioBpAfter").asInt());
        assertEquals(orderId, body.get("cancelledOrderIds").get(0).asLong());
        assertEquals("CANCELLED", jdbc.queryForObject(
                "SELECT status FROM liquidation_order WHERE id = ?", String.class, orderId));
        // 已撤销指令不可执行
        mockMvc.perform(post("/api/liquidations/{id}/execute", orderId))
                .andExpect(status().isConflict());
    }
}
