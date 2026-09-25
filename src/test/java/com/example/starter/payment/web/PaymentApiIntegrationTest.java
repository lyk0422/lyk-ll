package com.example.starter.payment.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.example.starter.payment.AbstractIntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 真实 HTTP 入口端到端测试：入账→幂等/409→封账→挂账→调账→对账→差异核销，
 * 同时验证全局异常处理把业务冲突映射为 409。
 */
@AutoConfigureMockMvc
class PaymentApiIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void fullFlowOverHttp() throws Exception {
        // 1) 入账两笔（UTC 10:00，归属 2026-09-24 批次）。
        mockMvc.perform(post("/api/payments").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"req-1","channelSerialNo":"CH-1","merchantNo":"M001",
                                 "amount":"100.00","occurTime":"2026-09-24T10:00:00Z"}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("POSTED"))
                .andExpect(jsonPath("$.batchDate").value("2026-09-24"))
                .andExpect(jsonPath("$.replayed").value(false));

        mockMvc.perform(post("/api/payments").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"req-2","channelSerialNo":"CH-2","merchantNo":"M002",
                                 "amount":"200.00","occurTime":"2026-09-24T10:05:00Z"}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("POSTED"));

        // 2) 同 requestId 同参数：返回首次结果 replayed=true，仅 200。
        mockMvc.perform(post("/api/payments").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"req-1","channelSerialNo":"CH-1","merchantNo":"M001",
                                 "amount":"100.00","occurTime":"2026-09-24T10:00:00Z"}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.replayed").value(true));

        // 3) 同 requestId 异参数：409。
        mockMvc.perform(post("/api/payments").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"req-1","channelSerialNo":"CH-1","merchantNo":"M001",
                                 "amount":"999.00","occurTime":"2026-09-24T10:00:00Z"}"""))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409));

        // 4) 参数校验失败：400。
        mockMvc.perform(post("/api/payments").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"","channelSerialNo":"CH-X","merchantNo":"M001",
                                 "amount":"1.00","occurTime":"2026-09-24T10:00:00Z"}"""))
                .andExpect(status().isBadRequest());

        // 5) 封账：清算金额 = 交易额 - 手续费（M001：100 - 0.60 = 99.40）。
        mockMvc.perform(post("/api/batches/2026-09-24/close"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CLOSED"))
                .andExpect(jsonPath("$.replayed").value(false))
                .andExpect(jsonPath("$.items[0].settledAmount").value(99.40));

        // 重复封账：replayed=true。
        mockMvc.perform(post("/api/batches/2026-09-24/close"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.replayed").value(true));

        // 6) 封账后到达交易：挂账。
        mockMvc.perform(post("/api/payments").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"req-3","channelSerialNo":"CH-3","merchantNo":"M001",
                                 "amount":"40.00","occurTime":"2026-09-24T14:00:00Z"}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUSPENDED"));

        // 7) 调账入下一开放批次。
        mockMvc.perform(post("/api/adjustments").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"channelSerialNo":"CH-3","operator":"op01","remark":"挂账调次日"}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fromBatchDate").value("2026-09-24"))
                .andExpect(jsonPath("$.toBatchDate").value("2026-09-25"));

        // 8) 对账：渠道文件缺 CH-2（本地多账）、多 CH-9（渠道多账）、CH-1 金额不符（差错）。
        MvcResult reconResult = mockMvc.perform(post("/api/recon").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"batchDate":"2026-09-24","entries":[
                                  {"channelSerialNo":"CH-1","merchantNo":"M001","amount":"120.00"},
                                  {"channelSerialNo":"CH-9","merchantNo":"M002","amount":"30.00"}
                                ]}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.localExtra").value(1))
                .andExpect(jsonPath("$.channelExtra").value(1))
                .andExpect(jsonPath("$.amountMismatch").value(1))
                .andReturn();
        JsonNode reconBody = json.readTree(reconResult.getResponse().getContentAsString());
        assertThatNodeHas(reconBody, "matched", 0);

        // 9) 查询差异并核销本地多账（CH-2）。
        MvcResult diffsResult = mockMvc.perform(get("/api/recon/batches/2026-09-24/diffs"))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode diffs = json.readTree(diffsResult.getResponse().getContentAsString());
        long ch2DiffId = -1;
        for (JsonNode d : diffs) {
            if ("CH-2".equals(d.path("channelSerialNo").asText())) {
                ch2DiffId = d.path("id").asLong();
            }
        }
        org.assertj.core.api.Assertions.assertThat(ch2DiffId).isPositive();

        mockMvc.perform(post("/api/recon/diffs/" + ch2DiffId + "/resolve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"action":"WRITE_OFF","operator":"op01","remark":"核销本地多账"}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RESOLVED"))
                .andExpect(jsonPath("$.replayed").value(false));

        // 重复核销返回首次结果，不重复处理。
        mockMvc.perform(post("/api/recon/diffs/" + ch2DiffId + "/resolve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"action":"WRITE_OFF","operator":"op02","remark":"再次核销"}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.replayed").value(true));
    }

    private void assertThatNodeHas(JsonNode node, String field, int expected) {
        org.assertj.core.api.Assertions.assertThat(node.path(field).asInt()).isEqualTo(expected);
    }
}
