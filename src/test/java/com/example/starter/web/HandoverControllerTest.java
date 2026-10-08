package com.example.starter.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.example.starter.TestClockConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 交班 REST 入口测试（真实 HTTP 栈 + H2 事务）：
 * 端点拒绝返回 400、完整 02:00 交班主流程，以及交班后两人工时各 2 小时的查询结果。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestClockConfig.class)
class HandoverControllerTest {

    private static final LocalDateTime NOW = TestClockConfig.BASE_TIME;
    private static final LocalDateTime DAY = NOW.toLocalDate().plusDays(1).atTime(0, 0);

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM idempotency_record");
        jdbc.update("DELETE FROM violation_record");
        jdbc.update("DELETE FROM resource_lock");
        jdbc.update("DELETE FROM plan_tug");
        jdbc.update("DELETE FROM pilot_handover");
        jdbc.update("DELETE FROM pilot_plan");
        TestClockConfig.mutableClock().setNow(NOW);
    }

    private String json(Object value) throws Exception {
        return objectMapper.writeValueAsString(value);
    }

    /** Jackson 默认 ISO 输出带秒（2026-10-07T02:00:00），断言须与之同格式。 */
    private String iso(LocalDateTime time) {
        return time.format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss"));
    }

    private long createExecutingPlan00To04(String tag) throws Exception {
        MvcResult applyRes = mockMvc.perform(post("/api/pilot-plans/apply")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(java.util.Map.of(
                                "requestId", tag + "-apply",
                                "applicant", "测试船代",
                                "shipName", "合成轮-" + tag,
                                "planStart", DAY.toString(),
                                "planEnd", DAY.plusHours(4).toString()))))
                .andExpect(status().isOk())
                .andReturn();
        long planId = objectMapper.readTree(applyRes.getResponse().getContentAsString()).get("planId").asLong();

        mockMvc.perform(post("/api/pilot-plans/schedule")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(java.util.Map.of(
                                "requestId", tag + "-sched",
                                "planId", planId,
                                "pilotId", 1,
                                "tugIds", List.of(1, 2),
                                "tideStart", DAY.minusHours(1).toString(),
                                "tideEnd", DAY.plusHours(5).toString()))))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/pilot-plans/confirm")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(java.util.Map.of("requestId", tag + "-confirm", "planId", planId))))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/pilot-plans/start")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(java.util.Map.of("requestId", tag + "-start", "planId", planId))))
                .andExpect(status().isOk());
        return planId;
    }

    @Test
    @DisplayName("HTTP：交班时刻取计划起点（端点）返回 400，不接受端点")
    void endpointRejectedOverHttp() throws Exception {
        long planId = createExecutingPlan00To04("HEP");
        mockMvc.perform(post("/api/handovers/apply")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(java.util.Map.of(
                                "requestId", "HEP-ho",
                                "planId", planId,
                                "fromPilotId", 1,
                                "toPilotId", 2,
                                "handoverAt", DAY.toString()))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400));
    }

    @Test
    @DisplayName("HTTP 全流程：02:00 交班后查询两人工时各 120 分钟")
    void fullHandoverOverHttpSplitsWorkHours() throws Exception {
        long planId = createExecutingPlan00To04("HF");

        MvcResult applyRes = mockMvc.perform(post("/api/handovers/apply")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(java.util.Map.of(
                                "requestId", "HF-ho",
                                "planId", planId,
                                "fromPilotId", 1,
                                "toPilotId", 2,
                                "handoverAt", DAY.plusHours(2).toString()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.successorPreoccupied").value(true))
                .andReturn();
        JsonNode applied = objectMapper.readTree(applyRes.getResponse().getContentAsString());
        long handoverId = applied.get("handoverId").asLong();

        mockMvc.perform(post("/api/handovers/confirm")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(java.util.Map.of(
                                "requestId", "HF-from", "handoverId", handoverId,
                                "pilotId", 1, "comment", "P1 交清", "expectedVersion", 1))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.version").value(2));
        mockMvc.perform(post("/api/handovers/confirm")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(java.util.Map.of(
                                "requestId", "HF-to", "handoverId", handoverId,
                                "pilotId", 2, "comment", "P2 接稳", "expectedVersion", 2))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.version").value(3));

        // 未到点切换 → 409
        mockMvc.perform(post("/api/handovers/complete")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(java.util.Map.of(
                                "requestId", "HF-early", "handoverId", handoverId, "expectedVersion", 3))))
                .andExpect(status().isConflict());

        TestClockConfig.mutableClock().setNow(DAY.plusHours(2));
        mockMvc.perform(post("/api/handovers/complete")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(java.util.Map.of(
                                "requestId", "HF-done", "handoverId", handoverId, "expectedVersion", 3))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SWITCHED"))
                .andExpect(jsonPath("$.segments[0].pilotId").value(1))
                .andExpect(jsonPath("$.segments[0].end").value(iso(DAY.plusHours(2))))
                .andExpect(jsonPath("$.segments[1].pilotId").value(2))
                .andExpect(jsonPath("$.segments[1].start").value(iso(DAY.plusHours(2))));

        String day = DAY.toLocalDate().toString();
        mockMvc.perform(get("/api/pilot-plans/work-hours").param("pilotId", "1").param("day", day))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalMinutes").value(120));
        mockMvc.perform(get("/api/pilot-plans/work-hours").param("pilotId", "2").param("day", day))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalMinutes").value(120));
    }
}
