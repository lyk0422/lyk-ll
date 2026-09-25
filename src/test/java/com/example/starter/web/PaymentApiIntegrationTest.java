package com.example.starter.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.starter.AbstractIntegrationTest;
import java.time.Instant;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/** 端到端 HTTP 链路：入账→封账→对账→差异核销，及 409/422/400 状态码。 */
class PaymentApiIntegrationTest extends AbstractIntegrationTest {

    private static final LocalDate BATCH = LocalDate.of(2026, 9, 20);

    @Autowired
    private WebApplicationContext webApplicationContext;

    private MockMvc mockMvc;

    @org.junit.jupiter.api.BeforeEach
    void initMockMvc() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).build();
    }

    @Test
    void fullLifecycleOverHttp() throws Exception {
        // 1) 入账 100000 分，期望 201、POSTED、批次 2026-09-20、手续费 600。
        mockMvc.perform(post("/api/payments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"HTTP-1","channelFlowNo":"HTTP-FLOW-1",
                                 "merchantNo":"M001","amount":100000,
                                 "occurredAt":"2026-09-20T10:00:00Z"}"""))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("POSTED"))
                .andExpect(jsonPath("$.batchDate").value("2026-09-20"))
                .andExpect(jsonPath("$.fee").value(600));

        // 2) 同 requestId 异参 → 409。
        mockMvc.perform(post("/api/payments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"HTTP-1","channelFlowNo":"HTTP-FLOW-1",
                                 "merchantNo":"M001","amount":200000,
                                 "occurredAt":"2026-09-20T10:00:00Z"}"""))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFLICT"));

        // 3) 金额非法 → 400。
        mockMvc.perform(post("/api/payments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"HTTP-BAD","channelFlowNo":"HTTP-FLOW-BAD",
                                 "merchantNo":"M001","amount":-1,
                                 "occurredAt":"2026-09-20T10:00:00Z"}"""))
                .andExpect(status().isBadRequest());

        // 4) 封账，期望清算 99400 分。
        mockMvc.perform(post("/api/batches/close")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"batchDate\":\"2026-09-20\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CLOSED"))
                .andExpect(jsonPath("$.totalAmount").value(100000))
                .andExpect(jsonPath("$.settledAmount").value(99400))
                .andExpect(jsonPath("$.orders[0].merchantNo").value("M001"));

        // 5) 对账文件不含该流水 → 本地多账。
        MvcResult reconResult = mockMvc.perform(post("/api/reconciliations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"fileNo":"HTTP-FILE-1","batchDate":"2026-09-20","entries":[]}"""))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.localExtraCount").value(1))
                .andExpect(jsonPath("$.diffs[0].status").value("PENDING"))
                .andReturn();
        String body = reconResult.getResponse().getContentAsString();
        Long diffId = ((Number) com.jayway.jsonpath.JsonPath.read(body, "$.diffs[0].id")).longValue();

        // 6) 核销 → 200，再次核销 → 409。
        mockMvc.perform(post("/api/recon-diffs/" + diffId + "/resolve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"resolution\":\"WRITE_OFF\",\"resolvedBy\":\"auditor\",\"remark\":\"核销\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RESOLVED"))
                .andExpect(jsonPath("$.resolution").value("WRITE_OFF"));

        mockMvc.perform(post("/api/recon-diffs/" + diffId + "/resolve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"resolution\":\"REFUND\"}"))
                .andExpect(status().isConflict());

        // 7) 批次查询。
        mockMvc.perform(get("/api/batches/2026-09-20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CLOSED"));
    }

    @Test
    void postToClosedBatchSuspendsAndAdjustmentMovesIt() throws Exception {
        mockMvc.perform(post("/api/payments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"HTTP-2","channelFlowNo":"HTTP-FLOW-2",
                                 "merchantNo":"M002","amount":200000,
                                 "occurredAt":"2026-09-20T10:00:00Z"}"""))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/batches/close")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"batchDate\":\"2026-09-20\"}"))
                .andExpect(status().isOk());

        // 封账后到达 → 挂账。
        mockMvc.perform(post("/api/payments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"HTTP-3","channelFlowNo":"HTTP-FLOW-3",
                                 "merchantNo":"M002","amount":50000,
                                 "occurredAt":"2026-09-20T11:00:00Z"}"""))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("SUSPENDED"));

        // 不存在的挂账交易调账 → 422。
        mockMvc.perform(post("/api/adjustments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"adjustNo":"HTTP-ADJ-X","channelFlowNo":"NOT-EXIST",
                                 "fromBatchDate":"2026-09-20","toBatchDate":"2026-09-21"}"""))
                .andExpect(status().isUnprocessableEntity());

        // 正常调账 → 201。
        mockMvc.perform(post("/api/adjustments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"adjustNo":"HTTP-ADJ-1","channelFlowNo":"HTTP-FLOW-3",
                                 "fromBatchDate":"2026-09-20","toBatchDate":"2026-09-21"}"""))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("DONE"))
                .andExpect(jsonPath("$.toBatchDate").value("2026-09-21"));
    }
}
