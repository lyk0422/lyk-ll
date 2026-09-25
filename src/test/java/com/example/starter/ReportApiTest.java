package com.example.starter;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 执行回报与漏报：回报去重、状态不可改、漏报标记、迟到回报只记事件不改漏报事实。
 */
class ReportApiTest extends BaseIntegrationTest {

    private MvcResult report(String windowCode, String stationCode, String reportStatus) throws Exception {
        return mockMvc.perform(post("/api/reports")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"windowCode":"%s","stationCode":"%s","status":"%s"}
                                """.formatted(windowCode, stationCode, reportStatus)))
                .andReturn();
    }

    private void schedule(String requestId, String windowCode, String satellite, String station, int priority)
            throws Exception {
        mockMvc.perform(post("/api/schedule/requests")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"%s","satelliteCode":"%s","stationCode":"%s","windowCode":"%s","priority":%d}
                                """.formatted(requestId, satellite, station, windowCode, priority)))
                .andExpect(status().isOk());
    }

    private MvcResult markMissed(String now) throws Exception {
        return mockMvc.perform(post("/api/maintenance/mark-missed")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"now\":\"" + now + "\"}"))
                .andReturn();
    }

    @Test
    void duplicateReportAddsNoRecordAndStatusIsImmutable() throws Exception {
        report("W0008", "GS-BJ", "SUCCESS");
        mockMvc.perform(post("/api/reports")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"windowCode\":\"W0008\",\"stationCode\":\"GS-BJ\",\"status\":\"SUCCESS\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("DUPLICATE"))
                .andExpect(jsonPath("$.reportStatus").value("SUCCESS"));
        // 状态提交后不可改
        mockMvc.perform(post("/api/reports")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"windowCode\":\"W0008\",\"stationCode\":\"GS-BJ\",\"status\":\"FAILED\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REPORT_IMMUTABLE"));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM window_outcome", Integer.class));
    }

    @Test
    void invalidReportStatusReturns400() throws Exception {
        mockMvc.perform(post("/api/reports")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"windowCode\":\"W0008\",\"stationCode\":\"GS-BJ\",\"status\":\"OK\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_ARGUMENT"));
    }

    @Test
    void missedWindowMarkedAndLateReportOnlyRecordsEvent() throws Exception {
        // W0008 窗口结束于 2026-09-24 01:10 UTC，判定时刻之后仍未回报则漏报
        schedule("REQ-M1", "W0008", "SAT-A", "GS-BJ", 3);
        mockMvc.perform(post("/api/maintenance/mark-missed")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"now\":\"2026-09-25T00:00:00Z\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.marked").value(1))
                .andExpect(jsonPath("$.windowCodes[0]").value("W0008"));

        // 迟到回报：只记迟到事件，漏报事实不变
        mockMvc.perform(post("/api/reports")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"windowCode\":\"W0008\",\"stationCode\":\"GS-BJ\",\"status\":\"PARTIAL\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("LATE_RECORDED"));
        String state = jdbc.queryForObject(
                "SELECT state FROM window_outcome", String.class);
        assertEquals("MISSED", state);
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM late_report_event", Integer.class));

        // 重复迟到回报幂等，不新增事件
        report("W0008", "GS-BJ", "PARTIAL");
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM late_report_event", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM window_outcome", Integer.class));
    }

    @Test
    void reportedWindowIsNotMarkedMissed() throws Exception {
        schedule("REQ-N1", "W0009", "SAT-B", "GS-BJ", 3);
        report("W0009", "GS-BJ", "SUCCESS");
        mockMvc.perform(post("/api/maintenance/mark-missed")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"now\":\"2026-09-25T00:00:00Z\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.marked").value(0));
        String state = jdbc.queryForObject("SELECT state FROM window_outcome", String.class);
        assertEquals("REPORTED", state);
    }

    @Test
    void unscheduledWindowIsNotMarkedMissed() throws Exception {
        // 未编排的窗口不参与漏报标记
        mockMvc.perform(post("/api/maintenance/mark-missed")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"now\":\"2026-09-25T00:00:00Z\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.marked").value(0));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM window_outcome", Integer.class));
    }

    @Test
    void reportForUnknownWindowReturns404() throws Exception {
        mockMvc.perform(post("/api/reports")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"windowCode\":\"W9999\",\"stationCode\":\"GS-BJ\",\"status\":\"SUCCESS\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("WINDOW_NOT_FOUND"));
    }
}
