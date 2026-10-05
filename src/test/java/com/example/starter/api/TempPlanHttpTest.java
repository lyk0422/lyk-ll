package com.example.starter.api;

import com.example.starter.testsupport.TestClockConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 节假日临时加钞通道 HTTP 集成测试（H2 MySQL 模式）：预测生成、幂等重放、
 * 快速通道留痕查询与日结核对入口。测试时钟固定于 2026-09-20T08:00Z（节前 3 日窗口内）。
 */
@SpringBootTest(classes = {com.example.starter.StarterApplication.class, TestClockConfig.class})
@AutoConfigureMockMvc
class TempPlanHttpTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM idempotent_request");
        jdbc.update("DELETE FROM late_event");
        jdbc.update("DELETE FROM fast_track_audit");
        jdbc.update("DELETE FROM plan_discrepancy");
        jdbc.update("DELETE FROM escort_checkin");
        jdbc.update("DELETE FROM replenish_plan");
        jdbc.update("UPDATE atm SET frozen = 0");
        jdbc.update("UPDATE custodian SET frozen = 0");
    }

    private Map<String, Object> body(String requestId, Object... kv) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("requestId", requestId);
        for (int i = 0; i < kv.length; i += 2) {
            map.put((String) kv[i], kv[i + 1]);
        }
        return map;
    }

    private MvcResult postJson(String url, Map<String, Object> body) throws Exception {
        return mockMvc.perform(post(url)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andReturn();
    }

    @Test
    void temporary_generation_over_http_with_replay_and_daily_reconciliation() throws Exception {
        // 1. 节假日峰值预测生成临时计划（合成·秋分购物节，峰值 2.0 倍）
        MvcResult generated = postJson("/api/plans/temporary/generate",
                body("rid-forecast", "holidayDate", "2026-09-23"));
        org.junit.jupiter.api.Assertions.assertEquals(200, generated.getResponse().getStatus());
        long tempId = objectMapper.readTree(generated.getResponse().getContentAsString())
                .get("results").get(0).get("planId").asLong();
        org.junit.jupiter.api.Assertions.assertTrue(objectMapper
                .readTree(generated.getResponse().getContentAsString())
                .get("results").get(0).get("generated").asBoolean());

        // 新 requestId 当日重复预测：受理但不重复生成
        mockMvc.perform(post("/api/plans/temporary/generate").contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body("rid-forecast2", "holidayDate", "2026-09-23"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.holidayName").value("合成·秋分购物节"))
                .andExpect(jsonPath("$.results[0].generated").value(false));

        // 2. 同键同参重放：200 且同一计划，不重复生成
        mockMvc.perform(post("/api/plans/temporary/generate").contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body("rid-forecast", "holidayDate", "2026-09-23"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.results[0].planId").value(tempId));

        // 3. 同键异参：409
        mockMvc.perform(post("/api/plans/temporary/generate").contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body("rid-forecast", "holidayDate", "2026-09-25"))))
                .andExpect(status().isConflict());

        // 4. 临时计划详情：类型 TEMPORARY，2 小时押运时限
        mockMvc.perform(get("/api/plans/" + tempId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.planType").value("TEMPORARY"))
                .andExpect(jsonPath("$.status").value("APPROVED"))
                .andExpect(jsonPath("$.escortDeadline").value("2026-09-20T10:00:00Z"));

        // 5. 快速通道结论留痕
        mockMvc.perform(get("/api/plans/" + tempId + "/fast-track-audits"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].decision").value("AUTO_APPROVED"));

        // 6. 日结核对：当日计划台账 + 快速通道留痕
        mockMvc.perform(get("/api/reconciliation/daily").param("date", "2026-09-20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.plans.length()").value(3))
                .andExpect(jsonPath("$.fastTrackAudits.length()").value(3));
    }

    @Test
    void forecast_outside_window_returns_409_and_unknown_holiday_404() throws Exception {
        // 国庆节窗口为 09-28 ~ 09-30，当前 09-20 不在窗口内
        mockMvc.perform(post("/api/plans/temporary/generate").contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body("rid-window", "holidayDate", "2026-10-01"))))
                .andExpect(status().isConflict());

        mockMvc.perform(post("/api/plans/temporary/generate").contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body("rid-unknown", "holidayDate", "2026-12-25"))))
                .andExpect(status().isNotFound());
    }
}
