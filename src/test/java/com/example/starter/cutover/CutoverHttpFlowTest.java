package com.example.starter.cutover;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * HTTP 层端到端：申请 -> 影响分析 -> 审批 -> 执行 -> 回报 -> 验证 -> 封板，
 * 以及同键异参 409 与终态冲突 409。
 */
@AutoConfigureMockMvc
class CutoverHttpFlowTest extends AbstractCutoverTest {

    @Autowired
    private MockMvc mockMvc;

    private String applyBody(String requestId, String device, String priority,
                             String start, String end) {
        return """
                {
                  "requestId": "%s",
                  "deviceCode": "%s",
                  "title": "核心交换机固件升级",
                  "priority": "%s",
                  "windowStart": "%s",
                  "windowEnd": "%s",
                  "steps": [
                    {"seq": 1, "name": "备份配置", "stepType": "NORMAL"},
                    {"seq": 2, "name": "升级固件", "stepType": "NORMAL"},
                    {"seq": 3, "name": "业务拨测验证", "stepType": "VERIFY"}
                  ]
                }
                """.formatted(requestId, device, priority, start, end);
    }

    @Test
    void fullLifecycleOverHttp() throws Exception {
        MvcResult created = mockMvc.perform(post("/api/cutovers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(applyBody("REQ-HTTP-1", "DEV-CORE-SW-01", "NORMAL",
                                "2026-09-25T10:00:00", "2026-09-25T14:00:00")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("CREATED"))
                .andExpect(jsonPath("$.steps.length()").value(3))
                .andReturn();
        long id = ((Number) com.jayway.jsonpath.JsonPath.read(
                created.getResponse().getContentAsString(), "$.id")).longValue();

        mockMvc.perform(post("/api/cutovers/{id}/analysis", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ANALYZED"))
                .andExpect(jsonPath("$.affectedBusinesses.length()").value(3));

        mockMvc.perform(post("/api/cutovers/{id}/approvals", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"));

        mockMvc.perform(post("/api/cutovers/{id}/execution", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("EXECUTING"));

        for (int seq = 1; seq <= 3; seq++) {
            mockMvc.perform(post("/api/cutovers/{id}/steps/{seq}/reports", id, seq)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"requestId\":\"R-HTTP-S" + seq + "\",\"phase\":\"START\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.stepStatus").value("RUNNING"));
            mockMvc.perform(post("/api/cutovers/{id}/steps/{seq}/reports", id, seq)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"requestId\":\"R-HTTP-R" + seq
                                    + "\",\"phase\":\"RESULT\",\"result\":\"SUCCESS\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.stepStatus").value("DONE"));
        }

        mockMvc.perform(get("/api/cutovers/{id}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("VERIFIED"));

        mockMvc.perform(post("/api/cutovers/{id}/seals", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SEALED"))
                .andExpect(jsonPath("$.replay").value(false));

        // 重复封板幂等成功
        mockMvc.perform(post("/api/cutovers/{id}/seals", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.replay").value(true));
    }

    @Test
    void sameKeyDifferentParamsReturns409OverHttp() throws Exception {
        mockMvc.perform(post("/api/cutovers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(applyBody("REQ-HTTP-2", "DEV-CORE-SW-01", "NORMAL",
                                "2026-09-25T10:00:00", "2026-09-25T12:00:00")))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/cutovers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(applyBody("REQ-HTTP-2", "DEV-CORE-SW-01", "HIGH",
                                "2026-09-25T10:00:00", "2026-09-25T12:00:00")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_PARAM_MISMATCH"));
    }

    @Test
    void hardFailOverHttpTriggersRollbackAndSealConflicts() throws Exception {
        MvcResult created = mockMvc.perform(post("/api/cutovers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(applyBody("REQ-HTTP-3", "DEV-CORE-SW-02", "NORMAL",
                                "2026-09-25T10:00:00", "2026-09-25T14:00:00")))
                .andExpect(status().isCreated())
                .andReturn();
        long id = ((Number) com.jayway.jsonpath.JsonPath.read(
                created.getResponse().getContentAsString(), "$.id")).longValue();

        mockMvc.perform(post("/api/cutovers/{id}/analysis", id)).andExpect(status().isOk());
        mockMvc.perform(post("/api/cutovers/{id}/approvals", id)).andExpect(status().isOk());
        mockMvc.perform(post("/api/cutovers/{id}/execution", id)).andExpect(status().isOk());

        mockMvc.perform(post("/api/cutovers/{id}/steps/1/reports", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"R-H3-S1\",\"phase\":\"START\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/cutovers/{id}/steps/1/reports", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"R-H3-R1\",\"phase\":\"RESULT\",\"result\":\"HARD_FAIL\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rollbackTriggered").value(true))
                .andExpect(jsonPath("$.rollbackReason").value("HARD_CHECK_FAILED"))
                .andExpect(jsonPath("$.applicationStatus").value("ROLLED_BACK"));

        // 已回退不得封板
        mockMvc.perform(post("/api/cutovers/{id}/seals", id))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TERMINAL_CONFLICT"));

        // 回退后补报步骤只记迟到事件
        mockMvc.perform(post("/api/cutovers/{id}/steps/2/reports", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"R-H3-LATE\",\"phase\":\"RESULT\",\"result\":\"SUCCESS\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lateEvent").value(true))
                .andExpect(jsonPath("$.applicationStatus").value("ROLLED_BACK"));
    }

    @Test
    void unknownApplicationReturns404() throws Exception {
        mockMvc.perform(get("/api/cutovers/{id}", 999999))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }
}
