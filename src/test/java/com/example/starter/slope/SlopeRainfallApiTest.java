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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 雨量、策略、有效阈值与复工证据的 HTTP 入口测试：验证 REST 层、JSON 报文与错误语义。
 */
@SpringBootTest
@AutoConfigureMockMvc
class SlopeRainfallApiTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanTables() {
        truncateAll();
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

    private void truncateAll() {
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

    private long createZone(String requestId, String code) throws Exception {
        MvcResult zone = mockMvc.perform(post("/api/zones")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"%s","code":"%s","name":"雨量HTTP测区",
                                 "warnHourlyRate":5.0,"alarmDailyRate":50.0,"safetyHourlyRate":2.0,
                                 "personnelTotal":10}
                                """.formatted(requestId, code)))
                .andExpect(status().isCreated())
                .andReturn();
        String body = zone.getResponse().getContentAsString();
        return Long.parseLong(body.replaceAll(".*\"zoneId\":(\\d+).*", "$1"));
    }

    @Test
    void rainfallAndStrategyFlowOverHttp() throws Exception {
        long zoneId = createZone("http-z1", "HTTP-R1");

        // 上报雨量小时数据：201，版本1
        mockMvc.perform(post("/api/zones/{zoneId}/rainfall-hours", zoneId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"http-r1","hourStart":"2026-09-01T08:00:00",
                                 "rainfallMm":1.5,"expectedVersion":0}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.duplicated").value(false));

        // 同键同参重放：返回首次冻结结果
        mockMvc.perform(post("/api/zones/{zoneId}/rainfall-hours", zoneId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"http-r1","hourStart":"2026-09-01T08:00:00",
                                 "rainfallMm":1.5,"expectedVersion":0}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.version").value(1));

        // 同键异参：409
        mockMvc.perform(post("/api/zones/{zoneId}/rainfall-hours", zoneId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"http-r1","hourStart":"2026-09-01T09:00:00",
                                 "rainfallMm":1.5,"expectedVersion":0}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("IDEMPOTENCY_CONFLICT"));

        // 非整点：400
        mockMvc.perform(post("/api/zones/{zoneId}/rainfall-hours", zoneId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"http-r2","hourStart":"2026-09-01T08:30:00",
                                 "rainfallMm":1.5,"expectedVersion":0}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("HOUR_NOT_ALIGNED"));

        // 缺少必填字段：400
        mockMvc.perform(post("/api/zones/{zoneId}/rainfall-hours", zoneId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"hourStart":"2026-09-01T08:00:00","rainfallMm":1.5}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_FAILED"));

        // 发布策略：201，版本1
        mockMvc.perform(post("/api/zones/{zoneId}/strategies", zoneId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"http-s1","expectedVersion":0,
                                 "yellowHourlyRate":4.0,"redDailyRate":40.0,
                                 "rainfall24hThreshold":24.0,"reductionFactor":0.5,
                                 "submittedAt":"2026-09-01T08:00:00","effectiveFrom":"2026-09-01T10:00:00"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.version").value(1));

        // 策略列表查询
        mockMvc.perform(get("/api/zones/{zoneId}/strategies", zoneId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].yellowHourlyRate").value(4.0));

        // 有效阈值查询：雨量缺点时阈值不可定
        mockMvc.perform(get("/api/zones/{zoneId}/effective-thresholds", zoneId)
                        .param("at", "2026-09-02T10:00:00"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.strategyVersion").value(1))
                .andExpect(jsonPath("$.rainfallComplete").value(false));

        // 测区不存在：404
        mockMvc.perform(get("/api/zones/{zoneId}/effective-thresholds", 999999)
                        .param("at", "2026-09-02T10:00:00"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("ZONE_NOT_FOUND"));

        // 复工证据查询：链不存在404
        mockMvc.perform(get("/api/chains/{chainId}/resumption-evidence", 999999))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("CHAIN_NOT_FOUND"));
    }

    @Test
    void resumptionEvidenceQueryableOverHttp() throws Exception {
        long zoneId = createZone("http-z2", "HTTP-R2");
        // 红色预警 → 撤人 → 回落读数 → 申请复工，证据经HTTP可查
        mockMvc.perform(post("/api/zones/{zoneId}/readings", zoneId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"http-d0","measuredAt":"2026-09-01T08:00:00","displacementMm":0.0}
                                """))
                .andExpect(status().isCreated());
        MvcResult red = mockMvc.perform(post("/api/zones/{zoneId}/readings", zoneId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"http-d1","measuredAt":"2026-09-02T08:00:00","displacementMm":120.0}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.evaluation").value("RED_CHAIN_CREATED"))
                .andReturn();
        long chainId = Long.parseLong(red.getResponse().getContentAsString()
                .replaceAll(".*\"chainId\":(\\d+).*", "$1"));
        mockMvc.perform(post("/api/chains/{chainId}/evacuation-confirmations", chainId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"http-ev","confirmedAt":"2026-09-02T08:10:00","arrivedCount":10}
                                """))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/zones/{zoneId}/readings", zoneId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"http-d2","measuredAt":"2026-09-02T20:00:00","displacementMm":121.0}
                                """))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/zones/{zoneId}/readings", zoneId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"http-d3","measuredAt":"2026-09-03T08:00:00","displacementMm":122.0}
                                """))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/chains/{chainId}/resumption-applications", chainId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"http-a1","appliedAt":"2026-09-03T08:00:00"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("APPLIED"));

        mockMvc.perform(get("/api/chains/{chainId}/resumption-evidence", chainId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].phase").value("APPLY"))
                .andExpect(jsonPath("$[0].result").value("SAFE"))
                .andExpect(jsonPath("$[0].readingCount").value(2))
                .andExpect(jsonPath("$[0].windowStart").value("2026-09-02T08:00:00"));
    }
}
