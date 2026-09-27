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

import java.sql.Timestamp;
import java.time.LocalDateTime;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 雨量联动与复工门禁 HTTP 入口测试：覆盖策略发布/重放/异参409、小时雨量、
 * 有效阈值查询、复工证据冻结查询与 400/404 错误语义。
 */
@SpringBootTest
@AutoConfigureMockMvc
class RainfallPolicyApiTest {

    private static final LocalDateTime T0 = LocalDateTime.of(2026, 9, 1, 8, 0);

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanTables() {
        truncate();
    }

    @AfterAll
    static void releaseData(@Autowired JdbcTemplate jdbc) {
        jdbc.update("DELETE FROM resumption_evidence");
        jdbc.update("DELETE FROM idempotency_record");
        jdbc.update("DELETE FROM threshold_policy_version");
        jdbc.update("DELETE FROM rainfall_hour");
        jdbc.update("DELETE FROM late_event");
        jdbc.update("DELETE FROM resumption_assessment");
        jdbc.update("DELETE FROM evacuation_confirmation");
        jdbc.update("DELETE FROM disposal_chain");
        jdbc.update("DELETE FROM radar_reading");
        jdbc.update("DELETE FROM slope_zone");
    }

    private void truncate() {
        jdbcTemplate.update("DELETE FROM resumption_evidence");
        jdbcTemplate.update("DELETE FROM idempotency_record");
        jdbcTemplate.update("DELETE FROM threshold_policy_version");
        jdbcTemplate.update("DELETE FROM rainfall_hour");
        jdbcTemplate.update("DELETE FROM late_event");
        jdbcTemplate.update("DELETE FROM resumption_assessment");
        jdbcTemplate.update("DELETE FROM evacuation_confirmation");
        jdbcTemplate.update("DELETE FROM disposal_chain");
        jdbcTemplate.update("DELETE FROM radar_reading");
        jdbcTemplate.update("DELETE FROM slope_zone");
    }

    private long createZone(String code) throws Exception {
        MvcResult zone = mockMvc.perform(post("/api/zones")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"%s","code":"%s","name":"HTTP雨量测区",
                                 "warnHourlyRate":5.0,"alarmDailyRate":50.0,"safetyHourlyRate":2.0,
                                 "personnelTotal":10}
                                """.formatted("z-" + code, code)))
                .andExpect(status().isCreated())
                .andReturn();
        return Long.parseLong(zone.getResponse().getContentAsString()
                .replaceAll(".*\"zoneId\":(\\d+).*", "$1"));
    }

    /** 直接批量合成零雨量（08-31T00Z起120小时），HTTP测试只验证入口，不把百次调用当业务验证。 */
    private void seedZeroRain(long zoneId) {
        LocalDateTime start = LocalDateTime.of(2026, 8, 31, 0, 0);
        for (int i = 0; i < 120; i++) {
            jdbcTemplate.update(
                    "INSERT INTO rainfall_hour (zone_id, hour_utc, rain_mm, version) VALUES (?, ?, 0.0, 1)",
                    zoneId, Timestamp.valueOf(start.plusHours(i)));
        }
    }

    @Test
    void rainLinkedPolicyAndResumptionEvidenceFlowOverHttp() throws Exception {
        long zoneId = createZone("HP-1");

        // 发布策略 v1：门槛999（零雨不折减），2026-08-31即生效
        String publish = """
                {"requestId":"hp-p1","expectedVersion":0,"yellowHourlyRate":5.0,
                 "redDailyRate":50.0,"rainGateMm":999.0,"reductionFactor":1.0,
                 "submittedAt":"2026-08-31T00:00:00","effectiveAt":"2026-08-31T00:00:00"}
                """;
        mockMvc.perform(post("/api/zones/{zoneId}/threshold-policies", zoneId)
                        .contentType(MediaType.APPLICATION_JSON).content(publish))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.tightenedFromPrevious").value(false));
        // 同键同参重放
        mockMvc.perform(post("/api/zones/{zoneId}/threshold-policies", zoneId)
                        .contentType(MediaType.APPLICATION_JSON).content(publish))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.version").value(1));
        // 同键异参 409
        mockMvc.perform(post("/api/zones/{zoneId}/threshold-policies", zoneId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(publish.replace("\"yellowHourlyRate\":5.0",
                                "\"yellowHourlyRate\":4.0")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("IDEMPOTENCY_CONFLICT"));
        // 非法折减系数 400
        mockMvc.perform(post("/api/zones/{zoneId}/threshold-policies", zoneId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"hp-bad","expectedVersion":1,"yellowHourlyRate":5.0,
                                 "redDailyRate":50.0,"rainGateMm":10.0,"reductionFactor":1.5,
                                 "submittedAt":"2026-09-01T00:00:00","effectiveAt":"2026-09-01T01:00:00"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("INVALID_REDUCTION_FACTOR"));
        // 期望版本冲突 409
        mockMvc.perform(post("/api/zones/{zoneId}/threshold-policies", zoneId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"hp-p2-stale","expectedVersion":0,"yellowHourlyRate":4.0,
                                 "redDailyRate":50.0,"rainGateMm":999.0,"reductionFactor":1.0,
                                 "submittedAt":"2026-09-01T00:00:00","effectiveAt":"2026-09-01T01:00:00"}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("POLICY_VERSION_CONFLICT"));

        // 小时雨量写入 + 非整点400
        mockMvc.perform(post("/api/zones/{zoneId}/rainfall-hours", zoneId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"hp-rain1","hourUtc":"2026-09-10T00:00:00",
                                 "rainMm":2.5,"expectedVersion":0}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.outcome").value("CREATED"))
                .andExpect(jsonPath("$.version").value(1));
        mockMvc.perform(post("/api/zones/{zoneId}/rainfall-hours", zoneId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"hp-rain-bad","hourUtc":"2026-09-10T00:30:00",
                                 "rainMm":2.5,"expectedVersion":0}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("INVALID_RAIN_HOUR"));

        seedZeroRain(zoneId);

        // 有效阈值查询：零雨不折减
        mockMvc.perform(get("/api/zones/{zoneId}/effective-threshold", zoneId)
                        .param("at", "2026-09-03T20:00:00"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.policyVersion").value(1))
                .andExpect(jsonPath("$.reductionApplied").value(false))
                .andExpect(jsonPath("$.dataSufficient").value(true))
                .andExpect(jsonPath("$.effectiveYellowHourlyRate").value(5.0));
        mockMvc.perform(get("/api/zones/{zoneId}/effective-threshold", zoneId))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_FAILED"));

        // 红色链 → 撤人 → 安全读数 → 申请 → 双确认 → 冻结证据
        mockMvc.perform(post("/api/zones/{zoneId}/readings", zoneId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"requestId":"hp-r0","measuredAt":"2026-09-01T08:00:00","displacementMm":0.0}
                        """)).andExpect(status().isCreated())
                .andExpect(jsonPath("$.evaluation").value("NORMAL"));
        MvcResult red = mockMvc.perform(post("/api/zones/{zoneId}/readings", zoneId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"hp-r1","measuredAt":"2026-09-02T08:00:00","displacementMm":120.0}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.evaluation").value("RED_CHAIN_CREATED"))
                .andReturn();
        long chainId = Long.parseLong(red.getResponse().getContentAsString()
                .replaceAll(".*\"chainId\":(\\d+).*", "$1"));
        mockMvc.perform(post("/api/chains/{chainId}/evacuation-confirmations", chainId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"requestId":"hp-ev","confirmedAt":"2026-09-02T08:10:00","arrivedCount":10}
                        """)).andExpect(status().isCreated());
        for (int h = 25; h <= 60; h++) {
            LocalDateTime t = T0.plusHours(h);
            double disp = 120.0 + 0.1 * (h - 24);
            mockMvc.perform(post("/api/zones/{zoneId}/readings", zoneId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"requestId":"hp-s%d","measuredAt":"%s","displacementMm":%s}
                                    """.formatted(h, t, disp)))
                    .andExpect(status().isCreated());
        }
        mockMvc.perform(post("/api/chains/{chainId}/resumption-applications", chainId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"hp-a1","appliedAt":"2026-09-03T20:00:00"}
                                """))
                .andExpect(status().isCreated());
        // 批准前证据404
        mockMvc.perform(get("/api/chains/{chainId}/resumption-evidence", chainId))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("RESUMPTION_EVIDENCE_NOT_FOUND"));
        mockMvc.perform(post("/api/chains/{chainId}/resumption-confirmations", chainId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"hp-c1","type":"SITE_CHECK",
                                 "confirmedAt":"2026-09-03T20:05:00"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.assessmentStatus").value("APPLIED"));
        mockMvc.perform(post("/api/chains/{chainId}/resumption-confirmations", chainId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"hp-c2","type":"MONITORING_REVIEW",
                                 "confirmedAt":"2026-09-03T20:10:00"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.assessmentStatus").value("APPROVED"))
                .andExpect(jsonPath("$.chainStatus").value("RESUMED"))
                .andExpect(jsonPath("$.zoneStatus").value("NORMAL"));

        // 冻结证据查询
        mockMvc.perform(get("/api/chains/{chainId}/resumption-evidence", chainId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.chainId").value(chainId))
                .andExpect(jsonPath("$.frozenAt").value("2026-09-03T20:10:00"))
                .andExpect(jsonPath("$.windowStart").value("2026-09-02T08:00:00"))
                .andExpect(jsonPath("$.lastTightenedAt").doesNotExist())
                .andExpect(jsonPath("$.snapshot.policyVersions.length()").value(1))
                .andExpect(jsonPath("$.snapshot.confirmations.length()").value(2))
                .andExpect(jsonPath("$.snapshot.moments.length()").value(36))
                .andExpect(jsonPath("$.snapshot.moments[0].safe").value(true));
        mockMvc.perform(get("/api/zones/{zoneId}/threshold-policies", zoneId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));
        mockMvc.perform(get("/api/zones/{zoneId}/rainfall-hours", zoneId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(121));
        // 不存在对象 404
        mockMvc.perform(get("/api/chains/999999/resumption-evidence"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("CHAIN_NOT_FOUND"));
    }
}
