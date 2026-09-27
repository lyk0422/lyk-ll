package com.example.starter.slope;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 雨量与策略版本相关端点的 HTTP 入口测试：报文、幂等重放与错误语义。
 */
@SpringBootTest
@AutoConfigureMockMvc
class SlopeStrategyApiTest {

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

    private long createZone(String code) throws Exception {
        MvcResult zone = mockMvc.perform(post("/api/zones")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"z-%s","code":"%s","name":"策略HTTP测区",
                                 "warnHourlyRate":5.0,"alarmDailyRate":50.0,"safetyHourlyRate":2.0,
                                 "personnelTotal":10}
                                """.formatted(code, code)))
                .andExpect(status().isCreated())
                .andReturn();
        String body = zone.getResponse().getContentAsString();
        return Long.parseLong(body.replaceAll(".*\"zoneId\":(\\d+).*", "$1"));
    }

    @Test
    void strategyPublishOverHttpWithReplayAndConflict() throws Exception {
        long zoneId = createZone("HS-1");
        String request = """
                {"requestId":"hs-st1","expectedVersion":0,"yellowRate":3.0,"redRate":10.0,
                 "rainfall24hThreshold":100.0,"reductionFactor":0.5,
                 "effectiveFrom":"2026-09-01T08:00:00","submittedAt":"2026-09-01T08:00:00"}
                """;
        MvcResult first = mockMvc.perform(post("/api/zones/{zoneId}/strategies", zoneId)
                        .contentType(MediaType.APPLICATION_JSON).content(request))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.version").value(1))
                .andReturn();
        MvcResult replay = mockMvc.perform(post("/api/zones/{zoneId}/strategies", zoneId)
                        .contentType(MediaType.APPLICATION_JSON).content(request))
                .andExpect(status().isCreated())
                .andReturn();
        assertEquals(first.getResponse().getContentAsString(),
                replay.getResponse().getContentAsString(), "同键同参重放首次报文");

        mockMvc.perform(post("/api/zones/{zoneId}/strategies", zoneId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"hs-st1","expectedVersion":0,"yellowRate":4.0,"redRate":10.0,
                                 "rainfall24hThreshold":100.0,"reductionFactor":0.5,
                                 "effectiveFrom":"2026-09-01T08:00:00","submittedAt":"2026-09-01T08:00:00"}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("IDEMPOTENCY_CONFLICT"));
    }

    @Test
    void strategyPublishValidationAndVersionConflictOverHttp() throws Exception {
        long zoneId = createZone("HS-2");
        // 折减系数超出(0,1] → 400
        mockMvc.perform(post("/api/zones/{zoneId}/strategies", zoneId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"hs-b1","expectedVersion":0,"yellowRate":3.0,"redRate":10.0,
                                 "rainfall24hThreshold":100.0,"reductionFactor":1.5,
                                 "effectiveFrom":"2026-09-01T08:00:00","submittedAt":"2026-09-01T08:00:00"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_FAILED"));
        // 黄不小于红 → 422
        mockMvc.perform(post("/api/zones/{zoneId}/strategies", zoneId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"hs-b2","expectedVersion":0,"yellowRate":10.0,"redRate":3.0,
                                 "rainfall24hThreshold":100.0,"reductionFactor":0.5,
                                 "effectiveFrom":"2026-09-01T08:00:00","submittedAt":"2026-09-01T08:00:00"}
                                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("THRESHOLD_ORDER_INVALID"));
        // 期望版本不符 → 409
        mockMvc.perform(post("/api/zones/{zoneId}/strategies", zoneId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"hs-b3","expectedVersion":5,"yellowRate":3.0,"redRate":10.0,
                                 "rainfall24hThreshold":100.0,"reductionFactor":0.5,
                                 "effectiveFrom":"2026-09-01T08:00:00","submittedAt":"2026-09-01T08:00:00"}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("VERSION_CONFLICT"));
        // 测区不存在 → 404
        mockMvc.perform(post("/api/zones/{zoneId}/strategies", 999999)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"hs-b4","expectedVersion":0,"yellowRate":3.0,"redRate":10.0,
                                 "rainfall24hThreshold":100.0,"reductionFactor":0.5,
                                 "effectiveFrom":"2026-09-01T08:00:00","submittedAt":"2026-09-01T08:00:00"}
                                """))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("ZONE_NOT_FOUND"));
    }

    @Test
    void rainfallReportOverHttp() throws Exception {
        long zoneId = createZone("HS-3");
        mockMvc.perform(post("/api/zones/{zoneId}/rainfall-hours", zoneId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"hr-1","hourStart":"2026-09-01T10:00:00",
                                 "rainfallMm":12.5,"expectedVersion":0}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.version").value(1));
        // 同值重放
        mockMvc.perform(post("/api/zones/{zoneId}/rainfall-hours", zoneId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"hr-2","hourStart":"2026-09-01T10:00:00",
                                 "rainfallMm":12.5,"expectedVersion":0}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.replayed").value(true));
        // 异值更正
        mockMvc.perform(post("/api/zones/{zoneId}/rainfall-hours", zoneId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"hr-3","hourStart":"2026-09-01T10:00:00",
                                 "rainfallMm":20.0,"expectedVersion":1}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.corrected").value(true))
                .andExpect(jsonPath("$.version").value(2));
        // 非整点 → 400
        mockMvc.perform(post("/api/zones/{zoneId}/rainfall-hours", zoneId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"hr-4","hourStart":"2026-09-01T10:30:00",
                                 "rainfallMm":1.0,"expectedVersion":0}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("RAINFALL_HOUR_NOT_ALIGNED"));
        // 负雨量 → 400
        mockMvc.perform(post("/api/zones/{zoneId}/rainfall-hours", zoneId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"hr-5","hourStart":"2026-09-01T11:00:00",
                                 "rainfallMm":-1.0,"expectedVersion":0}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_FAILED"));
    }

    @Test
    void effectiveThresholdQueryOverHttp() throws Exception {
        long zoneId = createZone("HS-4");
        mockMvc.perform(post("/api/zones/{zoneId}/strategies", zoneId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"ht-st","expectedVersion":0,"yellowRate":3.0,"redRate":10.0,
                                 "rainfall24hThreshold":100.0,"reductionFactor":0.5,
                                 "effectiveFrom":"2026-09-01T08:00:00","submittedAt":"2026-09-01T08:00:00"}
                                """))
                .andExpect(status().isCreated());
        // 无雨量数据：数据不足，不启用折减
        mockMvc.perform(get("/api/zones/{zoneId}/effective-threshold", zoneId)
                        .param("at", "2026-09-03T08:00:00"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.strategyVersion").value(1))
                .andExpect(jsonPath("$.rainfallDataComplete").value(false))
                .andExpect(jsonPath("$.reductionApplied").value(false))
                .andExpect(jsonPath("$.missingHours.length()").value(24));
        // 缺少at参数 → 400
        mockMvc.perform(get("/api/zones/{zoneId}/effective-threshold", zoneId))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_FAILED"));
        // 测区不存在 → 404
        mockMvc.perform(get("/api/zones/{zoneId}/effective-threshold", 999999)
                        .param("at", "2026-09-03T08:00:00"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("ZONE_NOT_FOUND"));
    }

    @Test
    void resumptionEvidenceQueryOverHttp() throws Exception {
        long zoneId = createZone("HS-5");
        mockMvc.perform(post("/api/zones/{zoneId}/readings", zoneId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"he-r0","measuredAt":"2026-09-01T08:00:00","displacementMm":0.0}
                                """))
                .andExpect(status().isCreated());
        MvcResult red = mockMvc.perform(post("/api/zones/{zoneId}/readings", zoneId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"he-r1","measuredAt":"2026-09-02T08:00:00","displacementMm":120.0}
                                """))
                .andExpect(status().isCreated())
                .andReturn();
        long chainId = Long.parseLong(red.getResponse().getContentAsString()
                .replaceAll(".*\"chainId\":(\\d+).*", "$1"));
        mockMvc.perform(post("/api/chains/{chainId}/evacuation-confirmations", chainId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"he-ev","confirmedAt":"2026-09-02T08:10:00","arrivedCount":10}
                                """))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/zones/{zoneId}/readings", zoneId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"he-r2","measuredAt":"2026-09-02T20:00:00","displacementMm":121.0}
                                """))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/zones/{zoneId}/readings", zoneId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"he-r3","measuredAt":"2026-09-03T08:00:00","displacementMm":122.0}
                                """))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/chains/{chainId}/resumption-applications", chainId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"he-a1","appliedAt":"2026-09-03T08:00:00"}
                                """))
                .andExpect(status().isCreated());

        mockMvc.perform(get("/api/chains/{chainId}/resumption-evidence", chainId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].safetyThreshold").value(2.0))
                .andExpect(jsonPath("$[0].readingCount").value(2));
        mockMvc.perform(get("/api/chains/{chainId}/resumption-evidence", 999999))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("CHAIN_NOT_FOUND"));
    }
}
