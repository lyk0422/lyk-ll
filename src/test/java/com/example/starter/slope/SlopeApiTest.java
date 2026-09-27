package com.example.starter.slope;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * HTTP 入口测试：验证 REST 层、JSON 报文、幂等重放与错误语义。
 */
@SpringBootTest
@AutoConfigureMockMvc
class SlopeApiTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanTables() {
        jdbcTemplate.update("DELETE FROM idempotency_record");
        jdbcTemplate.update("DELETE FROM resumption_evidence");
        jdbcTemplate.update("DELETE FROM threshold_strategy");
        jdbcTemplate.update("DELETE FROM rainfall_hourly");
        jdbcTemplate.update("DELETE FROM late_event");
        jdbcTemplate.update("DELETE FROM resumption_assessment");
        jdbcTemplate.update("DELETE FROM evacuation_confirmation");
        jdbcTemplate.update("DELETE FROM disposal_chain");
        jdbcTemplate.update("DELETE FROM radar_reading");
        jdbcTemplate.update("DELETE FROM slope_zone");
    }

    @AfterAll
    static void releaseData(@Autowired JdbcTemplate jdbc) {
        jdbc.update("DELETE FROM idempotency_record");
        jdbc.update("DELETE FROM resumption_evidence");
        jdbc.update("DELETE FROM threshold_strategy");
        jdbc.update("DELETE FROM rainfall_hourly");
        jdbc.update("DELETE FROM late_event");
        jdbc.update("DELETE FROM resumption_assessment");
        jdbc.update("DELETE FROM evacuation_confirmation");
        jdbc.update("DELETE FROM disposal_chain");
        jdbc.update("DELETE FROM radar_reading");
        jdbc.update("DELETE FROM slope_zone");
    }

    @Test
    void redAlarmFlowOverHttp() throws Exception {
        MvcResult zone = mockMvc.perform(post("/api/zones")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"api-z1","code":"API-Z1","name":"HTTP测区",
                                 "warnHourlyRate":5.0,"alarmDailyRate":50.0,"safetyHourlyRate":2.0,
                                 "personnelTotal":10}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("NORMAL"))
                .andReturn();
        String body = zone.getResponse().getContentAsString();
        long zoneId = Long.parseLong(body.replaceAll(".*\"zoneId\":(\\d+).*", "$1"));

        mockMvc.perform(post("/api/zones/{zoneId}/readings", zoneId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"api-r0","measuredAt":"2026-09-01T08:00:00","displacementMm":0.0}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.evaluation").value("NORMAL"));

        mockMvc.perform(post("/api/zones/{zoneId}/readings", zoneId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"api-r1","measuredAt":"2026-09-02T08:00:00","displacementMm":120.0}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.evaluation").value("RED_CHAIN_CREATED"))
                .andExpect(jsonPath("$.zoneStatus").value("STOPPED"));

        // HTTP 层同键同参重放：返回首次结果
        mockMvc.perform(post("/api/zones/{zoneId}/readings", zoneId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"api-r1","measuredAt":"2026-09-02T08:00:00","displacementMm":120.0}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.evaluation").value("RED_CHAIN_CREATED"));

        // HTTP 层同键异参：409
        mockMvc.perform(post("/api/zones/{zoneId}/readings", zoneId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"api-r1","measuredAt":"2026-09-02T09:00:00","displacementMm":121.0}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("IDEMPOTENCY_CONFLICT"));

        mockMvc.perform(get("/api/zones/{zoneId}/rate-curve", zoneId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.points.length()").value(2));
    }

    @Test
    void validationFailureReturns400() throws Exception {
        mockMvc.perform(post("/api/zones")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"api-bad","code":"API-BAD"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_FAILED"));
    }

    @Test
    void missingZoneReturns404() throws Exception {
        mockMvc.perform(get("/api/zones/{zoneId}/rate-curve", 999999))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("ZONE_NOT_FOUND"));
    }

    @Test
    void replayReturnsIdenticalBody() throws Exception {
        String request = """
                {"requestId":"api-z2","code":"API-Z2","name":"重放测区",
                 "warnHourlyRate":5.0,"alarmDailyRate":50.0,"safetyHourlyRate":2.0,
                 "personnelTotal":8}
                """;
        MvcResult first = mockMvc.perform(post("/api/zones")
                        .contentType(MediaType.APPLICATION_JSON).content(request))
                .andExpect(status().isCreated()).andReturn();
        MvcResult replay = mockMvc.perform(post("/api/zones")
                        .contentType(MediaType.APPLICATION_JSON).content(request))
                .andExpect(status().isCreated()).andReturn();
        assertEquals(first.getResponse().getContentAsString(),
                replay.getResponse().getContentAsString(), "同键同参重放返回首次报文");
    }
}
