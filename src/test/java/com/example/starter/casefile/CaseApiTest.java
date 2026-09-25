package com.example.starter.casefile;

import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import com.example.starter.BaseIntegrationTest;

/**
 * 案件结案测试：结案为终态、重复结案幂等成功、结案后迟到过磅数据只记迟到事件。
 */
class CaseApiTest extends BaseIntegrationTest {

    /** 造一条超限处罚及其案件，返回案件编号（本测试每条用例仅一单，案件 id 为 1）。 */
    private void createPenaltyCase(String plateNo) throws Exception {
        String weigh = """
                {"requestId":"cw-%s","plateNo":"%s","stationId":"ST01","weighedAt":"2026-09-20T08:00:00Z",
                 "axleType":"AXLE_2","weighType":"DYNAMIC","totalWeightKg":23400}
                """.formatted(plateNo, plateNo);
        mockMvc.perform(post("/api/weigh-records")
                        .contentType(MediaType.APPLICATION_JSON).content(weigh))
                .andExpect(status().isCreated());
        String penalty = "{\"requestId\":\"cp-%s\",\"weighRecordId\":1}".formatted(plateNo);
        mockMvc.perform(post("/api/penalties")
                        .contentType(MediaType.APPLICATION_JSON).content(penalty))
                .andExpect(status().isCreated());
    }

    @Test
    @DisplayName("结案为终态：重复结案返回幂等成功")
    void closeIsTerminalAndIdempotent() throws Exception {
        createPenaltyCase("苏D00001");

        mockMvc.perform(post("/api/cases/1/close"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("CLOSED")))
                .andExpect(jsonPath("$.closedAt", notNullValue()))
                .andExpect(jsonPath("$.idempotentReplay", is(false)));

        // 重复结案：幂等成功，状态不变
        mockMvc.perform(post("/api/cases/1/close"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("CLOSED")))
                .andExpect(jsonPath("$.idempotentReplay", is(true)));

        Long closedCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM case_record WHERE id = 1 AND status = 'CLOSED'", Long.class);
        Assertions.assertEquals(1L, closedCount);
    }

    @Test
    @DisplayName("结案后迟到过磅数据只记迟到事件入证据备查，不改判已结案件")
    void lateWeighAfterCloseOnlyRecordsEvent() throws Exception {
        createPenaltyCase("苏D00002");
        mockMvc.perform(post("/api/cases/1/close"))
                .andExpect(status().isOk());

        // 结案后到达的同一车辆过磅数据（过磅时刻早于结案时间）
        String lateWeigh = """
                {"requestId":"cw-late-1","plateNo":"苏D00002","stationId":"ST01",
                 "weighedAt":"2026-09-20T09:00:00Z","axleType":"AXLE_2",
                 "weighType":"DYNAMIC","totalWeightKg":30000}
                """;
        mockMvc.perform(post("/api/weigh-records")
                        .contentType(MediaType.APPLICATION_JSON).content(lateWeigh))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.lateEvidence", is(true)))
                .andExpect(jsonPath("$.overLimit", is(true)));

        // 迟到事件已登记，案件状态与原处罚不受影响
        Long events = jdbc.queryForObject(
                "SELECT COUNT(*) FROM late_weigh_event WHERE case_id = 1", Long.class);
        Assertions.assertEquals(1L, events);
        mockMvc.perform(get("/api/cases/1"))
                .andExpect(jsonPath("$.status", is("CLOSED")));
        String penaltyStatus = jdbc.queryForObject(
                "SELECT status FROM penalty_decision WHERE id = 1", String.class);
        Assertions.assertEquals("ACTIVE", penaltyStatus);

        // 迟到数据不得据以作出新处罚
        String penalty = "{\"requestId\":\"cp-late\",\"weighRecordId\":2}";
        mockMvc.perform(post("/api/penalties")
                        .contentType(MediaType.APPLICATION_JSON).content(penalty))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code", is("LATE_EVIDENCE")));
    }

    @Test
    @DisplayName("结案前正常过磅数据不记迟到事件")
    void weighBeforeCloseIsNotLate() throws Exception {
        createPenaltyCase("苏D00003");
        String weigh = """
                {"requestId":"cw-normal","plateNo":"苏D00003","stationId":"ST02",
                 "weighedAt":"2026-09-21T08:00:00Z","axleType":"AXLE_2",
                 "weighType":"DYNAMIC","totalWeightKg":20000}
                """;
        mockMvc.perform(post("/api/weigh-records")
                        .contentType(MediaType.APPLICATION_JSON).content(weigh))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.lateEvidence", is(false)));
        Long events = jdbc.queryForObject("SELECT COUNT(*) FROM late_weigh_event", Long.class);
        Assertions.assertEquals(0L, events);
    }
}
