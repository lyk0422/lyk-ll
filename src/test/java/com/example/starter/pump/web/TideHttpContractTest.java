package com.example.starter.pump.web;

import com.example.starter.pump.testsupport.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 潮汐接口 HTTP 契约：202 受理、400 参数错误、404 对象不存在、409 版本/幂等冲突、
 * 422 业务拒绝，以及潮位/候选/工况轨迹查询。
 */
@AutoConfigureMockMvc
class TideHttpContractTest extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    private void createControlAndWarningAndCandidate() throws Exception {
        mockMvc.perform(post("/api/tide-controls").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"tc-1","zoneCode":"ZN","threshold":4.500}
                                """))
                .andExpect(status().isAccepted());
        mockMvc.perform(post("/api/warnings").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"w-1","zoneCode":"ZN","alertLevel":"ORANGE",
                                 "alertTime":"2026-09-26T08:00:00"}
                                """))
                .andExpect(status().isAccepted());
        mockMvc.perform(post("/api/tide-readings").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"tr-1","zoneCode":"ZN",
                                 "readingTime":"2026-09-26T00:05:00Z","level":4.500}
                                """))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.direction").value("ENTER_FORCED"));
    }

    /** 当前唯一候选的ID（自增序列跨用例不复位，不能硬编码）。 */
    private long candidateId() {
        Long id = jdbc.queryForObject("SELECT id FROM mode_switch_candidate", Long.class);
        return id == null ? -1L : id;
    }

    @Test
    void fullTideFlow_overHttp_configIngestReceiptConfirmAndQueries() throws Exception {
        createControlAndWarningAndCandidate();
        long candidateId = candidateId();

        // 潮位查询
        mockMvc.perform(get("/api/zones/ZN/tide-readings"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.readings.length()").value(1))
                .andExpect(jsonPath("$.readings[0].late").value(false));

        // 候选查询：固定两站闸门目标
        mockMvc.perform(get("/api/tide-candidates/" + candidateId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.pinnedVersion").value(1))
                .andExpect(jsonPath("$.targets.length()").value(2));

        // 回执上报
        mockMvc.perform(post("/api/tide-candidates/" + candidateId + "/gate-receipts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"gr-1","stationCode":"PS-01","opening":80.00}
                                """))
                .andExpect(status().isAccepted());
        mockMvc.perform(post("/api/tide-candidates/" + candidateId + "/gate-receipts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"gr-2","stationCode":"PS-02","opening":75.00}
                                """))
                .andExpect(status().isAccepted());

        // 确认切换
        mockMvc.perform(post("/api/tide-candidates/" + candidateId + "/confirm")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"cf-1","expectedVersion":1,
                                 "switchTime":"2026-09-26T08:30:00"}
                                """))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.mode").value("FORCED"))
                .andExpect(jsonPath("$.newVersion").value(2))
                .andExpect(jsonPath("$.taskIds.length()").value(2));

        // 工况轨迹查询
        mockMvc.perform(get("/api/zones/ZN/mode-trajectory"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.currentMode").value("FORCED"))
                .andExpect(jsonPath("$.currentVersion").value(2))
                .andExpect(jsonPath("$.switches.length()").value(1));
    }

    @Test
    void negativeLevel_returns400() throws Exception {
        mockMvc.perform(post("/api/tide-controls").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"tc-1","zoneCode":"ZN","threshold":4.500}
                                """))
                .andExpect(status().isAccepted());
        mockMvc.perform(post("/api/tide-readings").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"tr-neg","zoneCode":"ZN",
                                 "readingTime":"2026-09-26T00:05:00Z","level":-0.100}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void missingVersion_returns400() throws Exception {
        mockMvc.perform(post("/api/tide-candidates/1/confirm")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"cf-x","switchTime":"2026-09-26T08:30:00"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void unknownCandidate_returns404() throws Exception {
        mockMvc.perform(get("/api/tide-candidates/999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("CANDIDATE_NOT_FOUND"));
    }

    @Test
    void unconfiguredZone_returns404_andKeepsOriginalBehavior() throws Exception {
        mockMvc.perform(post("/api/tide-readings").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"tr-0","zoneCode":"ZN",
                                 "readingTime":"2026-09-26T00:05:00Z","level":4.500}
                                """))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("TIDE_CONTROL_NOT_FOUND"));
        mockMvc.perform(get("/api/zones/ZN/mode-trajectory"))
                .andExpect(status().isNotFound());
    }

    @Test
    void versionMismatch_returns409WithCurrentVersion() throws Exception {
        mockMvc.perform(post("/api/tide-controls").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"tc-1","zoneCode":"ZN","threshold":4.500}
                                """))
                .andExpect(status().isAccepted());
        mockMvc.perform(put("/api/tide-controls/ZN").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"tc-u","threshold":4.800,"expectedVersion":9}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("VERSION_CONFLICT"))
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("当前版本: 1")));
    }

    @Test
    void confirmWithoutReceipts_returns422() throws Exception {
        createControlAndWarningAndCandidate();
        mockMvc.perform(post("/api/tide-candidates/" + candidateId() + "/confirm")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"cf-1","expectedVersion":1,
                                 "switchTime":"2026-09-26T08:30:00"}
                                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("GATE_RECEIPT_INCOMPLETE"));
    }

    @Test
    void sameKeyDifferentParams_returns409() throws Exception {
        String body = """
                {"requestId":"tc-9","zoneCode":"ZN","threshold":4.500}
                """;
        mockMvc.perform(post("/api/tide-controls").contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isAccepted());
        mockMvc.perform(post("/api/tide-controls").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"tc-9","zoneCode":"ZN","threshold":5.500}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENT_CONFLICT"));
    }
}
