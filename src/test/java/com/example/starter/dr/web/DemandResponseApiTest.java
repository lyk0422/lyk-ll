package com.example.starter.dr.web;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 需求响应 API 的 HTTP 主流程与错误语义测试（MockMvc + 真实 H2）。
 */
@SpringBootTest
@AutoConfigureMockMvc
class DemandResponseApiTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM adjustment_advice");
        jdbc.update("DELETE FROM settlement_interval");
        jdbc.update("DELETE FROM settlement");
        jdbc.update("DELETE FROM freeze_request");
        jdbc.update("DELETE FROM curve_point_current");
        jdbc.update("DELETE FROM curve_point_history");
        jdbc.update("DELETE FROM dr_enrollment");
        jdbc.update("DELETE FROM dr_event");
    }

    @Test
    void fullFlowCreateEnrollWriteFreezeQueryOverHttp() throws Exception {
        mockMvc.perform(post("/api/dr/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"eventId":"E1","start":"2026-03-01T00:00:00Z","end":"2026-03-01T01:00:00Z","intervalMinutes":30}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.intervalCount").value(2))
                .andExpect(jsonPath("$.version").value(1));

        mockMvc.perform(post("/api/dr/events/E1/enrollments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"meterId":"M1","committedKw":40,"baselineVersion":"BL-1"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.enrollments[0].meterId").value("M1"));

        mockMvc.perform(post("/api/dr/events/E1/meters/M1/curves/BASELINE")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"points":[{"intervalIndex":0,"value":100},{"intervalIndex":1,"value":90}]}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dataVersion").value(1));
        mockMvc.perform(post("/api/dr/events/E1/meters/M1/curves/ACTUAL")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"points":[{"intervalIndex":0,"value":70},{"intervalIndex":1,"value":90}]}
                                """))
                .andExpect(status().isOk());

        // idx0 实减 30（未超承诺），idx1 为 0 → 合计 30；事件版本=建1+登记1+基线1+实测1=4
        mockMvc.perform(post("/api/dr/events/E1/settlements/freeze")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"REQ-1","expectedVersion":4,"meters":[{"meterId":"M1","baselineVersion":1,"actualVersion":1}]}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.settlementId").value("STL-E1"))
                .andExpect(jsonPath("$.totalDeliveredKw").value(30.000000))
                .andExpect(jsonPath("$.intervals.length()").value(2))
                .andExpect(jsonPath("$.intervals[0].intervalStart").value("2026-03-01T00:00:00Z"));

        // 证据查询
        mockMvc.perform(get("/api/dr/events/E1/settlement"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requestId").value("REQ-1"))
                .andExpect(jsonPath("$.intervals[1].deliveredKw").value(0.000000));

        // 同键同指纹重放返回首次结果
        mockMvc.perform(post("/api/dr/events/E1/settlements/freeze")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"REQ-1","expectedVersion":4,"meters":[{"meterId":"M1","baselineVersion":1,"actualVersion":1}]}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalDeliveredKw").value(30.000000));

        // 同键异参 → 409
        mockMvc.perform(post("/api/dr/events/E1/settlements/freeze")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"REQ-1","expectedVersion":4,"meters":[{"meterId":"M1","baselineVersion":2,"actualVersion":1}]}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("CONFLICT"));

        // 冻结后更正 → 调整建议
        mockMvc.perform(post("/api/dr/events/E1/meters/M1/curves/ACTUAL")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"points":[{"intervalIndex":0,"value":60,"expectedVersion":1}]}
                                """))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/dr/events/E1/adjustments"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.adjustments.length()").value(1))
                .andExpect(jsonPath("$.totalDeltaKw").value(10.000000));
        // 冻结证据仍为旧值
        mockMvc.perform(get("/api/dr/events/E1/settlement"))
                .andExpect(jsonPath("$.intervals[0].actualValue").value(70.000000));
    }

    @Test
    void missingValueReturns422() throws Exception {
        mockMvc.perform(post("/api/dr/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"eventId":"E2","start":"2026-03-01T00:00:00Z","end":"2026-03-01T00:30:00Z","intervalMinutes":30}
                                """))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/dr/events/E2/enrollments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"meterId":"M1","committedKw":40,"baselineVersion":"BL-1"}
                                """))
                .andExpect(status().isCreated());
        // 只写基线，缺实测
        mockMvc.perform(post("/api/dr/events/E2/meters/M1/curves/BASELINE")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"points":[{"intervalIndex":0,"value":100}]}
                                """))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/dr/events/E2/settlements/freeze")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"REQ-2","expectedVersion":3,"meters":[{"meterId":"M1","baselineVersion":1,"actualVersion":1}]}
                                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("PENDING")));
    }

    @Test
    void invalidRequestAndUnknownResourceReturn400And404() throws Exception {
        // 结束早于开始 → 400
        mockMvc.perform(post("/api/dr/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"eventId":"E3","start":"2026-03-01T01:00:00Z","end":"2026-03-01T00:00:00Z","intervalMinutes":30}
                                """))
                .andExpect(status().isBadRequest());
        // 未知事件 → 404
        mockMvc.perform(get("/api/dr/events/NOPE/settlement"))
                .andExpect(status().isNotFound());
    }
}
