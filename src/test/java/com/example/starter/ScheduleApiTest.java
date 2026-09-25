package com.example.starter;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 编排主流程与失败分支：半开窗口冲突、优先级抢占、同级先到保留、幂等重放、发布冻结与版本。
 */
class ScheduleApiTest extends BaseIntegrationTest {

    /**
     * 按窗口编码提交申请，卫星/地面站自动取窗口所属方。
     */
    private MvcResult submitForWindow(String requestId, String windowCode, int priority) throws Exception {
        String satellite = jdbc.queryForObject(
                "SELECT s.code FROM pass_window w JOIN satellite s ON s.id = w.satellite_id "
                        + "WHERE w.window_code = ?", String.class, windowCode);
        String station = jdbc.queryForObject(
                "SELECT g.code FROM pass_window w JOIN ground_station g ON g.id = w.station_id "
                        + "WHERE w.window_code = ?", String.class, windowCode);
        return mockMvc.perform(post("/api/schedule/requests")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"%s","satelliteCode":"%s","stationCode":"%s","windowCode":"%s","priority":%d}
                                """.formatted(requestId, satellite, station, windowCode, priority)))
                .andReturn();
    }

    private MvcResult publish(String day) throws Exception {
        return mockMvc.perform(post("/api/schedule/publications")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"day\":\"" + day + "\"}"))
                .andReturn();
    }

    @Test
    void endpointTouchingWindowsAreNotConflict() throws Exception {
        // W0001 [01:00,01:10) 与 W0003 [01:10,01:20) 端点相接，不算冲突
        submitForWindow("REQ-T1", "W0001", 3);
        mockMvc.perform(post("/api/schedule/requests")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"REQ-T2\",\"satelliteCode\":\"SAT-C\","
                                + "\"stationCode\":\"GS-BJ\",\"windowCode\":\"W0003\",\"priority\":3}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("SCHEDULED"));
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM schedule_entry WHERE status = 'SCHEDULED'", Integer.class);
        assertEquals(2, count);
    }

    @Test
    void interiorOverlapSamePriorityKeepsFirstComer() throws Exception {
        // W0001 [01:00,01:10) 与 W0002 [01:05,01:15) 内部重叠，同优先级后到者拒绝
        submitForWindow("REQ-F1", "W0001", 3);
        mockMvc.perform(post("/api/schedule/requests")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"REQ-F2\",\"satelliteCode\":\"SAT-B\","
                                + "\"stationCode\":\"GS-BJ\",\"windowCode\":\"W0002\",\"priority\":3}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("WINDOW_CONFLICT"))
                .andExpect(jsonPath("$.details[0].windowCode").value("W0001"))
                .andExpect(jsonPath("$.details[0].requestId").value("REQ-F1"));
        Integer scheduled = jdbc.queryForObject(
                "SELECT COUNT(*) FROM schedule_entry WHERE status = 'SCHEDULED'", Integer.class);
        assertEquals(1, scheduled);
    }

    @Test
    void higherPriorityPreemptsAndGeneratesReplanSuggestion() throws Exception {
        submitForWindow("REQ-P1", "W0001", 2);
        mockMvc.perform(post("/api/schedule/requests")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"REQ-P2\",\"satelliteCode\":\"SAT-B\","
                                + "\"stationCode\":\"GS-BJ\",\"windowCode\":\"W0002\",\"priority\":5}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("PREEMPTED_OTHERS"))
                .andExpect(jsonPath("$.preempted[0].windowCode").value("W0001"))
                .andExpect(jsonPath("$.preempted[0].suggestedWindowCodes[0]").value("W0004"));
        String status = jdbc.queryForObject(
                "SELECT status FROM schedule_entry WHERE request_id = 'REQ-P1'", String.class);
        assertEquals("PREEMPTED", status);
        String preemptedBy = jdbc.queryForObject(
                "SELECT preempted_by_request_id FROM schedule_entry WHERE request_id = 'REQ-P1'",
                String.class);
        assertEquals("REQ-P2", preemptedBy);
        Integer suggestions = jdbc.queryForObject(
                "SELECT COUNT(*) FROM replan_suggestion", Integer.class);
        assertEquals(1, suggestions);
    }

    @Test
    void lowerPriorityCannotPreempt() throws Exception {
        submitForWindow("REQ-L1", "W0001", 4);
        mockMvc.perform(post("/api/schedule/requests")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"REQ-L2\",\"satelliteCode\":\"SAT-B\","
                                + "\"stationCode\":\"GS-BJ\",\"windowCode\":\"W0002\",\"priority\":2}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("WINDOW_CONFLICT"));
    }

    @Test
    void sameKeySameParamsReplaysFirstResult() throws Exception {
        MvcResult first = submitForWindow("REQ-I1", "W0001", 3);
        assertEquals(200, first.getResponse().getStatus());
        MvcResult replay = submitForWindow("REQ-I1", "W0001", 3);
        assertEquals(200, replay.getResponse().getStatus());
        assertEquals(first.getResponse().getContentAsString(),
                replay.getResponse().getContentAsString());
        Integer requests = jdbc.queryForObject("SELECT COUNT(*) FROM schedule_request", Integer.class);
        Integer entries = jdbc.queryForObject("SELECT COUNT(*) FROM schedule_entry", Integer.class);
        assertEquals(1, requests);
        assertEquals(1, entries);
    }

    @Test
    void sameKeyDifferentParamsReturns409AndKeepsSchedule() throws Exception {
        submitForWindow("REQ-K1", "W0001", 3);
        mockMvc.perform(post("/api/schedule/requests")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"REQ-K1\",\"satelliteCode\":\"SAT-A\","
                                + "\"stationCode\":\"GS-BJ\",\"windowCode\":\"W0004\",\"priority\":3}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_CONFLICT"));
        Integer entries = jdbc.queryForObject("SELECT COUNT(*) FROM schedule_entry", Integer.class);
        assertEquals(1, entries);
    }

    @Test
    void rejectionIsStoredAndReplayedAsFirstResult() throws Exception {
        submitForWindow("REQ-R1", "W0001", 3);
        MvcResult rejected = submitForWindow("REQ-R2", "W0002", 3);
        assertEquals(409, rejected.getResponse().getStatus());
        MvcResult replay = submitForWindow("REQ-R2", "W0002", 3);
        assertEquals(409, replay.getResponse().getStatus());
        assertEquals(rejected.getResponse().getContentAsString(),
                replay.getResponse().getContentAsString());
        Integer requests = jdbc.queryForObject("SELECT COUNT(*) FROM schedule_request", Integer.class);
        Integer entries = jdbc.queryForObject("SELECT COUNT(*) FROM schedule_entry", Integer.class);
        assertEquals(2, requests);
        assertEquals(1, entries);
    }

    @Test
    void publishFreezesDayAndRepublishIsIdempotent() throws Exception {
        MvcResult submitted = submitForWindow("REQ-D1", "W0004", 3);
        Number entryId = com.jayway.jsonpath.JsonPath.read(
                submitted.getResponse().getContentAsString(), "$.entryId");

        publish("2026-09-26");
        mockMvc.perform(post("/api/schedule/publications")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"day\":\"2026-09-26\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.idempotentReplay").value(true));

        // 发布后抢占申请一律拒绝
        mockMvc.perform(post("/api/schedule/requests")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"REQ-D2\",\"satelliteCode\":\"SAT-B\","
                                + "\"stationCode\":\"GS-BJ\",\"windowCode\":\"W0005\",\"priority\":5}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DAY_FROZEN"));
        // 发布后撤销申请一律拒绝
        mockMvc.perform(post("/api/schedule/entries/" + entryId + "/cancel"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DAY_FROZEN"));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM publication", Integer.class));
    }

    @Test
    void emergencyInsertGoesThroughNewVersionAndOldVersionQueryable() throws Exception {
        submitForWindow("REQ-E1", "W0001", 3);
        publish("2026-09-26");
        // 紧急插入：与已发布条目不冲突的新窗口，作为草稿受理
        submitForWindow("REQ-E2", "W0004", 3);
        mockMvc.perform(post("/api/schedule/publications")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"day\":\"2026-09-26\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(2))
                .andExpect(jsonPath("$.entryCount").value(1))
                .andExpect(jsonPath("$.idempotentReplay").value(false));

        mockMvc.perform(get("/api/schedule/publications").param("day", "2026-09-26"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2));
        // 旧版本可查：v1 仅含 W0001，v2 含 W0001+W0004
        mockMvc.perform(get("/api/schedule/publications/2026-09-26/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].windowCode").value("W0001"));
        mockMvc.perform(get("/api/schedule/publications/2026-09-26/2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2));
    }

    @Test
    void cancelBeforePublishIsIdempotent() throws Exception {
        MvcResult submitted = submitForWindow("REQ-C1", "W0001", 3);
        Number entryId = com.jayway.jsonpath.JsonPath.read(
                submitted.getResponse().getContentAsString(), "$.entryId");
        mockMvc.perform(post("/api/schedule/entries/" + entryId + "/cancel"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));
        mockMvc.perform(post("/api/schedule/entries/" + entryId + "/cancel"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));
        // 撤销后占用键释放，同窗口可重新申请
        submitForWindow("REQ-C2", "W0001", 3);
        String status = jdbc.queryForObject(
                "SELECT status FROM schedule_entry WHERE request_id = 'REQ-C2'", String.class);
        assertEquals("SCHEDULED", status);
    }

    @Test
    void unknownWindowReturns404() throws Exception {
        mockMvc.perform(post("/api/schedule/requests")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"REQ-X1\",\"satelliteCode\":\"SAT-A\","
                                + "\"stationCode\":\"GS-BJ\",\"windowCode\":\"W9999\",\"priority\":3}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("WINDOW_NOT_FOUND"));
    }

    @Test
    void windowStationMismatchReturns400() throws Exception {
        mockMvc.perform(post("/api/schedule/requests")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"REQ-X2\",\"satelliteCode\":\"SAT-A\","
                                + "\"stationCode\":\"GS-SY\",\"windowCode\":\"W0001\",\"priority\":3}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("WINDOW_MISMATCH"));
    }

    @Test
    void invalidPriorityReturns400() throws Exception {
        mockMvc.perform(post("/api/schedule/requests")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"REQ-X3\",\"satelliteCode\":\"SAT-A\","
                                + "\"stationCode\":\"GS-BJ\",\"windowCode\":\"W0001\",\"priority\":9}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_ARGUMENT"));
    }
}
