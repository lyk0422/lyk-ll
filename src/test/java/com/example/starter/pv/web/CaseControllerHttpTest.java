package com.example.starter.pv.web;

import com.example.starter.pv.TestClockConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 个例管理接口 HTTP 语义测试：状态码、幂等重放、错误分支与批次快照查询。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestClockConfig.class)
class CaseControllerHttpTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void clean() {
        TestClockConfig.reset();
        jdbcTemplate.update("DELETE FROM pv_batch_item");
        jdbcTemplate.update("DELETE FROM pv_submission_batch");
        jdbcTemplate.update("DELETE FROM pv_follow_up");
        jdbcTemplate.update("DELETE FROM pv_submission");
        jdbcTemplate.update("DELETE FROM pv_case");
    }

    @Test
    void initialReportReplayAndConflictOverHttp() throws Exception {
        String body = """
                {
                  "requestId": "http-1",
                  "patientCode": "P-HTTP",
                  "patientName": "合成患者王二",
                  "drugCode": "D-HTTP",
                  "drugName": "合成药品X",
                  "reactionEvent": "过敏性休克",
                  "seriousness": "FATAL",
                  "awarenessDate": "2026-01-10",
                  "submittedAt": "2026-01-16T23:59:59Z",
                  "lateReason": null
                }
                """;

        mockMvc.perform(post("/api/pv/initial-reports")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.merged").value(false))
                .andExpect(jsonPath("$.onTime").value(true))
                .andExpect(jsonPath("$.version").value(1));

        mockMvc.perform(post("/api/pv/initial-reports")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.merged").value(false));

        String changed = body.replace("2026-01-16T23:59:59Z", "2026-01-16T22:00:00Z");
        mockMvc.perform(post("/api/pv/initial-reports")
                        .contentType(MediaType.APPLICATION_JSON).content(changed))
                .andExpect(status().isConflict());
    }

    @Test
    void lateReportWithoutReasonReturns422() throws Exception {
        String body = """
                {
                  "requestId": "http-late",
                  "patientCode": "P-LATE",
                  "patientName": "合成患者李四",
                  "drugCode": "D-LATE",
                  "drugName": "合成药品Y",
                  "reactionEvent": "肝损伤",
                  "seriousness": "OTHER_SERIOUS",
                  "awarenessDate": "2026-01-10",
                  "submittedAt": "2026-01-25T00:00:00Z",
                  "lateReason": null
                }
                """;
        mockMvc.perform(post("/api/pv/initial-reports")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.status").value(422));
    }

    @Test
    void validationFailureReturns400() throws Exception {
        String body = """
                {
                  "requestId": "",
                  "patientCode": "P-V",
                  "patientName": "合成患者",
                  "drugCode": "D-V",
                  "drugName": "合成药品",
                  "reactionEvent": "皮疹",
                  "seriousness": "FATAL",
                  "awarenessDate": "2026-01-10",
                  "submittedAt": "2026-01-12T08:00:00Z"
                }
                """;
        mockMvc.perform(post("/api/pv/initial-reports")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest());
    }

    @Test
    void fullLifecycleFollowUpSubmitBatchAndCloseOverHttp() throws Exception {
        String initial = """
                {
                  "requestId": "http-life",
                  "patientCode": "P-LIFE",
                  "patientName": "合成患者赵五",
                  "drugCode": "D-LIFE",
                  "drugName": "合成药品Z",
                  "reactionEvent": "呼吸困难",
                  "seriousness": "FATAL",
                  "awarenessDate": "2026-01-01",
                  "submittedAt": "2026-01-02T08:00:00Z"
                }
                """;
        String caseNo = mockMvc.perform(post("/api/pv/initial-reports")
                        .contentType(MediaType.APPLICATION_JSON).content(initial))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString()
                .replaceAll(".*\"caseNo\":\"([^\"]+)\".*", "$1");

        String followUp = """
                {
                  "requestId": "http-fu",
                  "followUpDate": "2026-01-05",
                  "info": "症状缓解，继续观察",
                  "reporterCode": "RPT-9",
                  "reporterName": "合成报告人钱六"
                }
                """;
        mockMvc.perform(post("/api/pv/cases/" + caseNo + "/follow-ups")
                        .contentType(MediaType.APPLICATION_JSON).content(followUp))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.version").value(2))
                .andExpect(jsonPath("$.latestFollowUpDate").value("2026-01-05"));

        String batchReq = """
                { "requestId": "http-batch", "caseNos": ["%s"] }
                """.formatted(caseNo);
        String batchNo = mockMvc.perform(post("/api/pv/batches")
                        .contentType(MediaType.APPLICATION_JSON).content(batchReq))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.caseCount").value(1))
                .andExpect(jsonPath("$.items[0].version").value(2))
                .andReturn().getResponse().getContentAsString()
                .replaceAll(".*\"batchNo\":\"([^\"]+)\".*", "$1");

        mockMvc.perform(get("/api/pv/batches/" + batchNo))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].version").value(2));

        TestClockConfig.fixAt("2026-02-05T10:00:00Z");
        mockMvc.perform(post("/api/pv/cases/" + caseNo + "/close")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CLOSED"))
                .andExpect(jsonPath("$.idempotentReplay").value(false));

        mockMvc.perform(post("/api/pv/cases/" + caseNo + "/close")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.idempotentReplay").value(true));

        String followUpAfterClose = followUp.replace("http-fu", "http-fu-after-close");
        mockMvc.perform(post("/api/pv/cases/" + caseNo + "/follow-ups")
                        .contentType(MediaType.APPLICATION_JSON).content(followUpAfterClose))
                .andExpect(status().isConflict());

        mockMvc.perform(get("/api/pv/cases/" + caseNo))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CLOSED"));
    }

    @Test
    void missingCaseReturns404() throws Exception {
        mockMvc.perform(get("/api/pv/cases/PV-C-UNKNOWN"))
                .andExpect(status().isNotFound());
    }
}
