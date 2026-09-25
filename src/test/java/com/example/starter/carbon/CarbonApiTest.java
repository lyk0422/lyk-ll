package com.example.starter.carbon;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * REST API 集成测试：完整业务主流程、幂等重放/异参冲突、错误语义与凭证查询稳定性。
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:carbon_api_test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "carbon.compliance.deadline=2030-06-30T16:00:00Z"
})
@AutoConfigureMockMvc
@Import(MutableClockConfig.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CarbonApiTest {

    private static final Instant DEADLINE = Instant.parse("2030-06-30T16:00:00Z");

    @Autowired
    private MockMvc mvc;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private MutableClock clock;

    @BeforeEach
    void setUp() {
        clock.setInstant(DEADLINE.minus(Duration.ofDays(30)));
        jdbc.update("DELETE FROM surrender_item");
        jdbc.update("DELETE FROM certificate");
        jdbc.update("DELETE FROM surrender_request");
        jdbc.update("DELETE FROM compliance_status");
        jdbc.update("DELETE FROM allowance_batch");
        jdbc.update("DELETE FROM offset_credit_batch");
        jdbc.update("DELETE FROM emission_assessment");
        jdbc.update("DELETE FROM enterprise");
    }

    @AfterAll
    void releaseDatabase() {
        jdbc.execute("SHUTDOWN");
    }

    @Test
    void fullFlowSurrenderReplayAndCertificateQuery() throws Exception {
        postJson("/api/enterprises", """
                {"code":"E-API","name":"合成企业API"}""").andExpect(status().isCreated());
        postJson("/api/emissions", """
                {"enterpriseCode":"E-API","complianceYear":2030,"emissionAmount":1000}""")
                .andExpect(status().isNoContent());
        postJson("/api/allowances", """
                {"enterpriseCode":"E-API","vintageYear":2030,"amount":950,"expiryDate":"2031-12-31"}""")
                .andExpect(status().isCreated());
        postJson("/api/offsets", """
                {"enterpriseCode":"E-API","creditType":"CCER","amount":50,"expiryDate":"2031-06-30"}""")
                .andExpect(status().isCreated());

        // 清缴注销：配额 950 + 抵消 50（恰为 5% 上限）
        MvcResult surrender = postJson("/api/surrenders", """
                {"requestId":"REQ-API-1","enterpriseCode":"E-API","complianceYear":2030,
                 "allowanceAmount":950,"offsetAmount":50}""")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.idempotentReplay").value(false))
                .andExpect(jsonPath("$.items.length()").value(2))
                .andReturn();
        String certificateNo = com.jayway.jsonpath.JsonPath.read(
                surrender.getResponse().getContentAsString(), "$.certificateNo");
        assertThat(certificateNo).startsWith("CT2030-");

        // 同键同参重放：返回首次注销清单与同一凭证号
        postJson("/api/surrenders", """
                {"requestId":"REQ-API-1","enterpriseCode":"E-API","complianceYear":2030,
                 "allowanceAmount":950,"offsetAmount":50}""")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.idempotentReplay").value(true))
                .andExpect(jsonPath("$.certificateNo").value(certificateNo));

        // 同键异参：409 且账户不变
        postJson("/api/surrenders", """
                {"requestId":"REQ-API-1","enterpriseCode":"E-API","complianceYear":2030,
                 "allowanceAmount":949,"offsetAmount":51}""")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REQUEST_ID_CONFLICT"));

        // 凭证重复查询结果稳定
        MvcResult cert1 = mvc.perform(get("/api/certificates/" + certificateNo))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requestId").value("REQ-API-1"))
                .andExpect(jsonPath("$.totalAmount").value(1000))
                .andExpect(jsonPath("$.offsetAmount").value(50))
                .andReturn();
        MvcResult cert2 = mvc.perform(get("/api/certificates/" + certificateNo))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(cert2.getResponse().getContentAsString())
                .isEqualTo(cert1.getResponse().getContentAsString());

        // 履约状态：已足额履约
        mvc.perform(get("/api/compliance")
                        .param("enterpriseCode", "E-API")
                        .param("complianceYear", "2030"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLIANT"))
                .andExpect(jsonPath("$.surrenderedTotal").value(1000))
                .andExpect(jsonPath("$.offsetUsed").value(50));

        // 批次账户：配额冻结并注销后剩余为 0、状态 EXHAUSTED
        mvc.perform(get("/api/allowances").param("enterpriseCode", "E-API"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].remainingAmount").value(0))
                .andExpect(jsonPath("$[0].status").value("EXHAUSTED"));
    }

    @Test
    void overCapCombinationRejectedWith422() throws Exception {
        postJson("/api/enterprises", """
                {"code":"E-API-OVER","name":"合成企业超限"}""").andExpect(status().isCreated());
        postJson("/api/emissions", """
                {"enterpriseCode":"E-API-OVER","complianceYear":2030,"emissionAmount":1000}""")
                .andExpect(status().isNoContent());
        postJson("/api/offsets", """
                {"enterpriseCode":"E-API-OVER","creditType":"CCER","amount":60,"expiryDate":"2031-06-30"}""")
                .andExpect(status().isCreated());

        postJson("/api/surrenders", """
                {"requestId":"REQ-API-OVER","enterpriseCode":"E-API-OVER","complianceYear":2030,
                 "allowanceAmount":0,"offsetAmount":51}""")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("OFFSET_CAP_EXCEEDED"));

        mvc.perform(get("/api/offsets").param("enterpriseCode", "E-API-OVER"))
                .andExpect(jsonPath("$[0].remainingAmount").value(60));
    }

    @Test
    void overdueRequestRejectedWith422() throws Exception {
        postJson("/api/enterprises", """
                {"code":"E-API-LATE","name":"合成企业逾期"}""").andExpect(status().isCreated());
        postJson("/api/emissions", """
                {"enterpriseCode":"E-API-LATE","complianceYear":2030,"emissionAmount":100}""")
                .andExpect(status().isNoContent());
        postJson("/api/allowances", """
                {"enterpriseCode":"E-API-LATE","vintageYear":2030,"amount":100,"expiryDate":"2031-12-31"}""")
                .andExpect(status().isCreated());

        clock.setInstant(DEADLINE.plusSeconds(1));
        postJson("/api/surrenders", """
                {"requestId":"REQ-API-LATE","enterpriseCode":"E-API-LATE","complianceYear":2030,
                 "allowanceAmount":100,"offsetAmount":0}""")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("COMPLIANCE_DEADLINE_PASSED"));
    }

    @Test
    void unknownResourcesReturn404AndInvalidParamsReturn400() throws Exception {
        postJson("/api/surrenders", """
                {"requestId":"REQ-API-404","enterpriseCode":"E-NOPE","complianceYear":2030,
                 "allowanceAmount":1,"offsetAmount":0}""")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ENTERPRISE_NOT_FOUND"));

        mvc.perform(get("/api/certificates/CT2030-99999999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("CERTIFICATE_NOT_FOUND"));

        postJson("/api/enterprises", """
                {"code":"","name":""}""")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_ARGUMENT"));
    }

    private org.springframework.test.web.servlet.ResultActions postJson(String path, String body)
            throws Exception {
        return mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content(body));
    }
}
