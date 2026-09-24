package com.example.starter.carbon;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * HTTP 主流程测试：配额签发、抵消组合、清缴注销、凭证查询与错误语义（409/422）。
 */
@SpringBootTest
@AutoConfigureMockMvc
class CarbonApiTest {

    private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");

    @TestConfiguration
    static class ClockTestConfig {

        @Bean
        @Primary
        MutableClock mutableClock() {
            return new MutableClock(T0);
        }
    }

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private MutableClock clock;
    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void cleanUp() {
        jdbc.update("DELETE FROM surrender_item");
        jdbc.update("DELETE FROM surrender_request");
        jdbc.update("DELETE FROM offset_credit_batch");
        jdbc.update("DELETE FROM allowance_batch");
        jdbc.update("DELETE FROM compliance_account");
        jdbc.update("DELETE FROM enterprise");
        clock.setInstant(T0);
    }

    @Test
    void fullSurrenderFlowOverHttp() throws Exception {
        long enterpriseId = createEnterprise("合成企业-HTTP");
        setEmissions(enterpriseId, 1000);
        issueBatch("/api/allowances", enterpriseId, 1000);
        issueBatch("/api/offsets", enterpriseId, 50);

        // 清缴注销：配额 950 + 抵消信用 50（恰好 5%）
        MvcResult surrendered = mockMvc.perform(post("/api/surrenders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"req-http-1","enterpriseId":%d,"complianceYear":2026,
                                 "allowanceAmount":950,"offsetAmount":50}
                                """.formatted(enterpriseId)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.certificateNo").isString())
                .andExpect(jsonPath("$.items.length()").value(2))
                .andReturn();
        String certificateNo = com.jayway.jsonpath.JsonPath
                .read(surrendered.getResponse().getContentAsString(), "$.certificateNo");

        // 履约状态转为已足额清缴
        mockMvc.perform(get("/api/compliance/{enterpriseId}/{year}", enterpriseId, 2026))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SURRENDERED"))
                .andExpect(jsonPath("$.surrenderedAllowance").value(950))
                .andExpect(jsonPath("$.surrenderedOffset").value(50));

        // 凭证重复查询结果稳定
        mockMvc.perform(get("/api/surrenders/{requestId}", "req-http-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.certificateNo").value(certificateNo))
                .andExpect(jsonPath("$.items.length()").value(2));
    }

    @Test
    void idempotentConflictReturns409Body() throws Exception {
        long enterpriseId = createEnterprise("合成企业-409");
        setEmissions(enterpriseId, 2000);
        issueBatch("/api/allowances", enterpriseId, 2000);
        String body = """
                {"requestId":"req-http-409","enterpriseId":%d,"complianceYear":2026,
                 "allowanceAmount":%d,"offsetAmount":0}
                """;
        mockMvc.perform(post("/api/surrenders").contentType(MediaType.APPLICATION_JSON)
                        .content(body.formatted(enterpriseId, 2000)))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/surrenders").contentType(MediaType.APPLICATION_JSON)
                        .content(body.formatted(enterpriseId, 1900)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENT_CONFLICT"));
    }

    @Test
    void overdueSurrenderReturns422() throws Exception {
        long enterpriseId = createEnterprise("合成企业-逾期");
        setEmissions(enterpriseId, 500);
        issueBatch("/api/allowances", enterpriseId, 500);
        clock.setInstant(Instant.parse("2027-01-01T00:00:00Z"));

        mockMvc.perform(post("/api/surrenders").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"req-http-late","enterpriseId":%d,"complianceYear":2026,
                                 "allowanceAmount":500,"offsetAmount":0}
                                """.formatted(enterpriseId)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("SURRENDER_OVERDUE"));

        mockMvc.perform(get("/api/compliance/{enterpriseId}/{year}", enterpriseId, 2026))
                .andExpect(jsonPath("$.status").value("NON_COMPLIANT"));
    }

    @Test
    void unknownCertificateQueryReturns404() throws Exception {
        mockMvc.perform(get("/api/surrenders/{requestId}", "req-missing"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("SURRENDER_NOT_FOUND"));
    }

    private long createEnterprise(String name) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/enterprises")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + name + "\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        Integer id = com.jayway.jsonpath.JsonPath
                .read(result.getResponse().getContentAsString(), "$.enterpriseId");
        assertThat(id).isNotNull();
        return id.longValue();
    }

    private void setEmissions(long enterpriseId, long emissions) throws Exception {
        mockMvc.perform(put("/api/compliance/{enterpriseId}/{year}/emissions", enterpriseId, 2026)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"emissions":%d,"deadlineAt":"2026-12-31T00:00:00Z"}
                                """.formatted(emissions)))
                .andExpect(status().isOk());
    }

    private void issueBatch(String path, long enterpriseId, long quantity) throws Exception {
        mockMvc.perform(post(path)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"enterpriseId":%d,"complianceYear":2026,"quantity":%d,
                                 "expiryDate":"2027-06-30"}
                                """.formatted(enterpriseId, quantity)))
                .andExpect(status().isCreated());
    }
}
