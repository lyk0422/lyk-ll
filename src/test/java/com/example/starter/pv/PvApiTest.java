package com.example.starter.pv;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.example.starter.MutableClock;
import com.example.starter.TestClockConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * HTTP 层测试：初报→随访→递交→关闭主流程及错误语义（400/404/409）。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestClockConfig.class)
class PvApiTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private MutableClock clock;

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM pv_submission_batch_item");
        jdbc.update("DELETE FROM pv_submission_batch");
        jdbc.update("DELETE FROM pv_report");
        jdbc.update("DELETE FROM pv_case");
        clock.setInstant(java.time.Instant.parse("2026-01-01T00:00:00Z"));
    }

    private String initialBody(String requestId, String patient, String submittedAt, String lateReason) {
        return """
                {
                  "requestId": "%s",
                  "patientId": "%s",
                  "drugName": "合成药品A",
                  "adverseEvent": "合成皮疹事件",
                  "seriousness": "FATAL_OR_LIFE_THREATENING",
                  "awarenessDate": "2026-01-01",
                  "narrative": "合成叙述-%s",
                  "lateReason": %s,
                  "submittedAt": "%s"
                }
                """.formatted(requestId, patient, requestId,
                lateReason == null ? "null" : "\"" + lateReason + "\"", submittedAt);
    }

    @Test
    void fullFlow_initialFollowUpBatchClose() throws Exception {
        // 初报：及时（T0+7 日内）
        MvcResult initial = mvc.perform(post("/api/pv/initial-reports")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(initialBody("api-req-1", "P100", "2026-01-05T09:00:00Z", null)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.caseNo").exists())
                .andExpect(jsonPath("$.late").value(false))
                .andExpect(jsonPath("$.reportType").value("INITIAL"))
                .andReturn();
        JsonNode initialJson = objectMapper.readTree(initial.getResponse().getContentAsString());
        String caseNo = initialJson.get("caseNo").asText();

        // 随访
        mvc.perform(post("/api/pv/cases/{caseNo}/follow-ups", caseNo)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId": "api-req-2", "narrative": "合成随访信息",
                                 "submittedAt": "2026-01-10T09:00:00Z"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.caseNo").value(caseNo))
                .andExpect(jsonPath("$.reportType").value("FOLLOW_UP"))
                .andExpect(jsonPath("$.caseVersion").value(2));

        // 递交批次
        MvcResult batch = mvc.perform(post("/api/pv/submission-batches")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\": \"api-req-3\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.batchNo").exists())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].caseNo").value(caseNo))
                .andReturn();
        String batchNo = objectMapper.readTree(batch.getResponse().getContentAsString())
                .get("batchNo").asText();

        // 批次查询结果稳定
        mvc.perform(get("/api/pv/submission-batches/{batchNo}", batchNo))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].caseVersion").value(2));

        // 关闭（测试时钟固定于 2026-01-01，距最近随访不足 30 日 → 409）
        mvc.perform(post("/api/pv/cases/{caseNo}/close", caseNo))
                .andExpect(status().isConflict());

        // 个例查询
        mvc.perform(get("/api/pv/cases/{caseNo}", caseNo))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FOLLOW_UP_SUBMITTED"))
                .andExpect(jsonPath("$.reports.length()").value(2));
    }

    @Test
    void lateInitialWithoutReason_returns400() throws Exception {
        mvc.perform(post("/api/pv/initial-reports")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(initialBody("api-req-10", "P101", "2026-01-09T00:00:00Z", null)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void missingRequiredField_returns400() throws Exception {
        mvc.perform(post("/api/pv/initial-reports")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\": \"api-req-11\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void unknownCase_returns404() throws Exception {
        mvc.perform(get("/api/pv/cases/{caseNo}", "PV-99999999"))
                .andExpect(status().isNotFound());
        mvc.perform(post("/api/pv/cases/{caseNo}/follow-ups", "PV-99999999")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\": \"api-req-12\", \"narrative\": \"x\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void sameKeyDifferentParams_returns409() throws Exception {
        mvc.perform(post("/api/pv/initial-reports")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(initialBody("api-req-20", "P102", "2026-01-02T00:00:00Z", null)))
                .andExpect(status().isCreated());
        mvc.perform(post("/api/pv/initial-reports")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "requestId": "api-req-20",
                                  "patientId": "P102",
                                  "drugName": "合成药品A",
                                  "adverseEvent": "合成皮疹事件",
                                  "seriousness": "FATAL_OR_LIFE_THREATENING",
                                  "awarenessDate": "2026-01-01",
                                  "narrative": "被篡改的叙述",
                                  "submittedAt": "2026-01-02T00:00:00Z"
                                }
                                """))
                .andExpect(status().isConflict());
    }
}
