package com.example.starter.carbon;

import java.math.BigDecimal;

import com.example.starter.carbon.repo.CarbonLedgerRepository;
import com.example.starter.carbon.web.SurrenderRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.transaction.support.TransactionTemplate;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * REST 入口的 HTTP 语义测试：成功 201、规则拒绝 422、同键异参 409、参数非法 400、凭证不存在 404。
 */
class SurrenderWebApiTest extends AbstractCarbonIntegrationTest {

    private static final String ENT = "E-WEB";

    @Autowired
    private WebApplicationContext wac;
    @Autowired
    private ObjectMapper objectMapper;

    private MockMvc mockMvc;

    @BeforeEach
    void initMockMvc() {
        mockMvc = MockMvcBuilders.webAppContextSetup(wac).build();
    }

    private void seedScenario() {
        freezeAt("2026-01-01T00:00:00Z");
        long entId = new TransactionTemplate(txManager).execute(
                s -> ledger.insertEnterprise(ENT, "Web测试企业"));
        createAssessment(entId, 2025, "1000.000", "2026-06-30T16:00:00Z");
        createBatch(CarbonLedgerRepository.TYPE_ALLOWANCE, entId, "WAL-A", "950",
                "2025-01-01", "2026-12-31");
        createBatch(CarbonLedgerRepository.TYPE_OFFSET, entId, "WOF-A", "50",
                "2025-01-01", "2026-12-31");
    }

    private String body(String requestId, String allowance, String offset) throws Exception {
        return objectMapper.writeValueAsString(new SurrenderRequest(
                requestId, ENT, 2025, new BigDecimal(allowance), new BigDecimal(offset)));
    }

    @Test
    @DisplayName("成功清缴返回201并含注销清单与凭证号，随后凭证查询结果稳定")
    void successAndCertificateQuery() throws Exception {
        seedScenario();
        String response = mockMvc.perform(post("/api/surrender/requests")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("WREQ-1", "950", "50")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("RETIRED"))
                .andExpect(jsonPath("$.certificateNo").isNotEmpty())
                .andExpect(jsonPath("$.retirements.length()").value(2))
                .andExpect(jsonPath("$.retirements[0].accountType").value("ALLOWANCE"))
                .andExpect(jsonPath("$.retirements[1].accountType").value("OFFSET"))
                .andReturn().getResponse().getContentAsString();

        String certNo = objectMapper.readTree(response).get("certificateNo").asText();

        mockMvc.perform(get("/api/surrender/certificates/{certNo}", certNo))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.certificateNo").value(certNo))
                .andExpect(jsonPath("$.requestId").value("WREQ-1"))
                .andExpect(jsonPath("$.enterpriseCode").value(ENT))
                .andExpect(jsonPath("$.totalRetired").value(1000.000))
                .andExpect(jsonPath("$.offsetRetired").value(50.000));
    }

    @Test
    @DisplayName("超5%抵减整笔拒绝返回422")
    void offsetCapExceededReturns422() throws Exception {
        seedScenario();
        mockMvc.perform(post("/api/surrender/requests")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("WREQ-CAP", "949.999", "50.001")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("OFFSET_CAP_EXCEEDED"));
    }

    @Test
    @DisplayName("同键异参返回409")
    void idempotencyConflictReturns409() throws Exception {
        seedScenario();
        mockMvc.perform(post("/api/surrender/requests")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body("WREQ-DUP", "950", "50"))).andExpect(status().isCreated());
        mockMvc.perform(post("/api/surrender/requests")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("WREQ-DUP", "960", "40")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_CONFLICT"));
    }

    @Test
    @DisplayName("参数非法（缺requestId、负数量）返回400")
    void invalidPayloadReturns400() throws Exception {
        seedScenario();
        mockMvc.perform(post("/api/surrender/requests")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"","enterpriseCode":"%s","complianceYear":2025,
                                 "allowanceAmount":-1,"offsetAmount":0}
                                """.formatted(ENT)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("凭证不存在返回404")
    void missingCertificateReturns404() throws Exception {
        seedScenario();
        mockMvc.perform(get("/api/surrender/certificates/{certNo}", "CRT-MISSING"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }
}
