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
 * 雨量联动阈值版本 HTTP 入口测试：REST 层、JSON 报文、错误语义与查询端点。
 */
@SpringBootTest
@AutoConfigureMockMvc
class RainfallPolicyApiTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanTables() {
        truncateAll(jdbcTemplate);
    }

    @AfterAll
    static void releaseData(@Autowired JdbcTemplate jdbc) {
        truncateAll(jdbc);
    }

    private static void truncateAll(JdbcTemplate jdbc) {
        jdbc.update("DELETE FROM idempotency_record");
        jdbc.update("DELETE FROM resumption_evidence");
        jdbc.update("DELETE FROM threshold_policy");
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
                                {"requestId":"api-%s","code":"%s","name":"雨量HTTP测区",
                                 "warnHourlyRate":5.0,"alarmDailyRate":50.0,"safetyHourlyRate":2.0,
                                 "personnelTotal":10}
                                """.formatted(code, code)))
                .andExpect(status().isCreated())
                .andReturn();
        String body = zone.getResponse().getContentAsString();
        return Long.parseLong(body.replaceAll(".*\"zoneId\":(\\d+).*", "$1"));
    }

    @Test
    void rainfallReportCorrectAndQueryOverHttp() throws Exception {
        long zoneId = createZone("API-RF1");

        // 上报：201，版本1
        mockMvc.perform(post("/api/zones/{zoneId}/rainfall-hours", zoneId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"arf-1","hourStart":"2026-09-01T08:00:00",
                                 "rainfallMm":5.0,"reportedAt":"2026-09-01T09:00:00"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.replayed").value(false));

        // 同键同参重放：返回首次冻结结果
        mockMvc.perform(post("/api/zones/{zoneId}/rainfall-hours", zoneId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"arf-1","hourStart":"2026-09-01T08:00:00",
                                 "rainfallMm":5.0,"reportedAt":"2026-09-01T09:00:00"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.version").value(1));

        // 同值重放（不同requestId）：200
        mockMvc.perform(post("/api/zones/{zoneId}/rainfall-hours", zoneId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"arf-2","hourStart":"2026-09-01T08:00:00",
                                 "rainfallMm":5.0,"reportedAt":"2026-09-01T09:05:00"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.replayed").value(true));

        // 异值无更正版本：409
        mockMvc.perform(post("/api/zones/{zoneId}/rainfall-hours", zoneId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"arf-3","hourStart":"2026-09-01T08:00:00",
                                 "rainfallMm":6.0,"reportedAt":"2026-09-01T09:10:00"}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("RAINFALL_CORRECTION_REQUIRED"));

        // 显式更正：201，版本2
        mockMvc.perform(post("/api/zones/{zoneId}/rainfall-hours", zoneId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"arf-4","hourStart":"2026-09-01T08:00:00",
                                 "rainfallMm":6.0,"expectedVersion":1,"reportedAt":"2026-09-01T09:15:00"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.version").value(2));

        // 非整点：400；负值：400；缺requestId：400
        mockMvc.perform(post("/api/zones/{zoneId}/rainfall-hours", zoneId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"arf-5","hourStart":"2026-09-01T08:30:00",
                                 "rainfallMm":1.0,"reportedAt":"2026-09-01T09:00:00"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("INVALID_HOUR_START"));
        mockMvc.perform(post("/api/zones/{zoneId}/rainfall-hours", zoneId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"arf-6","hourStart":"2026-09-01T09:00:00",
                                 "rainfallMm":-1.0,"reportedAt":"2026-09-01T10:00:00"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("INVALID_RAINFALL_VALUE"));
        mockMvc.perform(post("/api/zones/{zoneId}/rainfall-hours", zoneId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"hourStart":"2026-09-01T10:00:00","rainfallMm":1.0,
                                 "reportedAt":"2026-09-01T11:00:00"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_FAILED"));

        // 测区不存在：404
        mockMvc.perform(post("/api/zones/{zoneId}/rainfall-hours", 999999)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"arf-7","hourStart":"2026-09-01T08:00:00",
                                 "rainfallMm":1.0,"reportedAt":"2026-09-01T09:00:00"}
                                """))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("ZONE_NOT_FOUND"));

        // 查询：当前值与更正历史
        mockMvc.perform(get("/api/zones/{zoneId}/rainfall-hours", zoneId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].rainfallMm").value(6.0))
                .andExpect(jsonPath("$[0].version").value(2));
        mockMvc.perform(get("/api/zones/{zoneId}/rainfall-corrections", zoneId)
                        .param("hour", "2026-09-01T08:00:00"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].rainfallMm").value(5.0))
                .andExpect(jsonPath("$[1].rainfallMm").value(6.0));
    }

    @Test
    void policyPublishAndQueryOverHttp() throws Exception {
        long zoneId = createZone("API-PP1");

        // 参数非法：400（黄不小于红）
        mockMvc.perform(post("/api/zones/{zoneId}/threshold-policies", zoneId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"app-0","expectedVersion":0,"yellowHourlyRate":8.0,
                                 "redHourlyRate":8.0,"rain24hThreshold":100.0,"reductionFactor":0.5,
                                 "effectiveAt":"2026-09-01T08:00:00","submittedAt":"2026-09-01T08:00:00"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("INVALID_POLICY_THRESHOLDS"));

        // 生效早于提交：400
        mockMvc.perform(post("/api/zones/{zoneId}/threshold-policies", zoneId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"app-0b","expectedVersion":0,"yellowHourlyRate":4.0,
                                 "redHourlyRate":8.0,"rain24hThreshold":100.0,"reductionFactor":0.5,
                                 "effectiveAt":"2026-09-01T07:00:00","submittedAt":"2026-09-01T08:00:00"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("EFFECTIVE_BEFORE_SUBMISSION"));

        // 发布v1：201
        mockMvc.perform(post("/api/zones/{zoneId}/threshold-policies", zoneId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"app-1","expectedVersion":0,"yellowHourlyRate":4.0,
                                 "redHourlyRate":8.0,"rain24hThreshold":100.0,"reductionFactor":0.5,
                                 "effectiveAt":"2026-09-01T08:00:00","submittedAt":"2026-09-01T08:00:00"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.version").value(1));

        // 版本冲突：409
        mockMvc.perform(post("/api/zones/{zoneId}/threshold-policies", zoneId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"app-2","expectedVersion":0,"yellowHourlyRate":3.0,
                                 "redHourlyRate":7.0,"rain24hThreshold":100.0,"reductionFactor":0.5,
                                 "effectiveAt":"2026-09-01T09:00:00","submittedAt":"2026-09-01T09:00:00"}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("VERSION_CONFLICT"));

        // 测区不存在：404
        mockMvc.perform(post("/api/zones/{zoneId}/threshold-policies", 999999)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"app-3","expectedVersion":0,"yellowHourlyRate":4.0,
                                 "redHourlyRate":8.0,"rain24hThreshold":100.0,"reductionFactor":0.5,
                                 "effectiveAt":"2026-09-01T08:00:00","submittedAt":"2026-09-01T08:00:00"}
                                """))
                .andExpect(status().isNotFound());

        // 策略版本查询
        mockMvc.perform(get("/api/zones/{zoneId}/threshold-policies", zoneId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].version").value(1))
                .andExpect(jsonPath("$[0].yellowHourlyRate").value(4.0));

        // 有效阈值查询：雨量未上报 → 数据不足
        mockMvc.perform(get("/api/zones/{zoneId}/effective-threshold", zoneId)
                        .param("at", "2026-09-02T08:00:00"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("INSUFFICIENT_DATA"))
                .andExpect(jsonPath("$.policyVersion").value(1))
                .andExpect(jsonPath("$.missingHours.length()").value(24));

        // 策略生效前时刻：无生效策略
        mockMvc.perform(get("/api/zones/{zoneId}/effective-threshold", zoneId)
                        .param("at", "2026-09-01T07:00:00"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("NO_POLICY"));

        // 复工证据查询：链不存在 → 404
        mockMvc.perform(get("/api/chains/{chainId}/resumption-evidence", 999999))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("CHAIN_NOT_FOUND"));
    }
}
