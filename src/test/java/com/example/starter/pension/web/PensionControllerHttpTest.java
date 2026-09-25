package com.example.starter.pension.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.example.starter.pension.testsupport.PensionTestConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * REST 入口 HTTP 层测试：真实 JSON 序列化与状态码，覆盖创建、幂等重放、同键异参 409、
 * 参数校验 400、逐期支付与自动结清。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(PensionTestConfig.class)
class PensionControllerHttpTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private ObjectMapper objectMapper;

    @BeforeEach
    void cleanTables() {
        PensionTestConfig.cleanup(jdbc);
    }

    @Test
    @DisplayName("HTTP全链路：登记→缴费→申请(同键重放200、异参409)→出款→自动结清→查询终态")
    void httpEndToEndAndStatusCodes() throws Exception {
        // 登记成员（司龄将恰满 5 年）。
        mockMvc.perform(post("/api/pension/members")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"memberNo":"W1","name":"王某某","hireDate":"2021-09-25","status":"RESIGNED"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.memberNo").value("W1"));

        // 企业缴费 100.00 元、个人缴费 40.00 元。
        mockMvc.perform(post("/api/pension/contributions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"memberNo":"W1","contributionDate":"2026-08-01","source":"EMPLOYER","amountCents":10000}
                                """))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/pension/contributions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"memberNo":"W1","contributionDate":"2026-08-01","source":"EMPLOYEE","amountCents":4000}
                                """))
                .andExpect(status().isCreated());

        // 参数校验失败：缺少 requestId -> 400。
        mockMvc.perform(post("/api/pension/payouts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"memberNo":"W1","reason":"RESIGNED","installmentType":"LUMP_SUM"}
                                """))
                .andExpect(status().isBadRequest());

        String applyBody = """
                {"requestId":"REQ-W1","memberNo":"W1","reason":"RESIGNED","installmentType":"LUMP_SUM"}
                """;
        MvcResult firstResult = mockMvc.perform(post("/api/pension/payouts")
                        .contentType(MediaType.APPLICATION_JSON).content(applyBody))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.totalAmountCents").value(12_000))
                .andExpect(jsonPath("$.employerForfeitCents").value(2_000))
                .andExpect(jsonPath("$.vestingRateBp").value(8_000))
                .andReturn();
        JsonNode firstJson = objectMapper.readTree(firstResult.getResponse().getContentAsString());
        String planNo = firstJson.get("planNo").asText();

        // 同键同参重放：200 级成功且同一计划号。
        mockMvc.perform(post("/api/pension/payouts")
                        .contentType(MediaType.APPLICATION_JSON).content(applyBody))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.planNo").value(planNo));

        // 同键异参：409，且不产生新计划。
        mockMvc.perform(post("/api/pension/payouts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"REQ-W1","memberNo":"W1","reason":"RESIGNED","installmentType":"MONTHLY_12"}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409));

        // 查询计划。
        mockMvc.perform(get("/api/pension/plans/{planNo}", planNo))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.installments[0].amountCents").value(12_000));

        // 出款：成功后自动结清。
        mockMvc.perform(post("/api/pension/payments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"PAY-W1-1","planNo":"%s","seqNo":1,"forceFail":false}
                                """.formatted(planNo)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result").value("SUCCESS"))
                .andExpect(jsonPath("$.planSettled").value(true))
                .andExpect(jsonPath("$.planStatus").value("SETTLED"));

        // 结清后重复支付：409。
        mockMvc.perform(post("/api/pension/payments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"PAY-W1-2","planNo":"%s","seqNo":1,"forceFail":false}
                                """.formatted(planNo)))
                .andExpect(status().isConflict());

        // 重复结清：幂等成功。
        mockMvc.perform(post("/api/pension/plans/{planNo}/settle", planNo))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.alreadySettled").value(true))
                .andExpect(jsonPath("$.companyReturnCents").value(2_000));

        // 不存在的计划：404。
        mockMvc.perform(get("/api/pension/plans/PL-NOPE"))
                .andExpect(status().isNotFound());
    }
}
