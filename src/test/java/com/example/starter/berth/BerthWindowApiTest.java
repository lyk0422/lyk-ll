package com.example.starter.berth;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/**
 * HTTP 层测试：验证建窗、冲突拒绝、审批生效、完成离泊的状态码与响应体。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestClockConfig.class)
class BerthWindowApiTest {

    @Autowired
    private MockMvc mvc;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private MutableClock clock;

    @BeforeEach
    void cleanUp() {
        jdbc.update("DELETE FROM window_pilot");
        jdbc.update("DELETE FROM berth_window");
        clock.setInstant(java.time.Instant.parse("2026-09-24T00:00:00Z"));
    }

    private static String body(String requestId, long berthId, String start, String end, String draft,
            int pilots) {
        return """
                {"requestId":"%s","vesselName":"VESSEL-%s","berthId":%d,"startTime":"%s","endTime":"%s","draft":%s,"pilotsRequired":%d}
                """.formatted(requestId, requestId, berthId, start, end, draft, pilots);
    }

    @Test
    void 建窗_审批_生效_离泊全流程() throws Exception {
        String json = mvc.perform(post("/api/berth-windows").contentType(MediaType.APPLICATION_JSON)
                .content(body("API-1", 1L, "2026-09-25T01:00:00Z", "2026-09-25T03:00:00Z", "10.0", 1)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andReturn().getResponse().getContentAsString();
        long id = ((Number) com.jayway.jsonpath.JsonPath.read(json, "$.id")).longValue();

        mvc.perform(post("/api/berth-windows/{id}/approve", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"))
                .andExpect(jsonPath("$.pilotIds[0]").value(1));

        clock.setInstant(java.time.Instant.parse("2026-09-25T01:00:00Z"));
        mvc.perform(get("/api/berth-windows/{id}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"));

        mvc.perform(post("/api/berth-windows/{id}/departure", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"));
    }

    @Test
    void 冲突拒绝返回409_限深拒绝返回422_参数缺失返回400() throws Exception {
        String created = mvc.perform(post("/api/berth-windows").contentType(MediaType.APPLICATION_JSON)
                .content(body("API-2", 1L, "2026-09-25T01:00:00Z", "2026-09-25T03:00:00Z", "10.0", 1)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        long id = ((Number) com.jayway.jsonpath.JsonPath.read(created, "$.id")).longValue();
        mvc.perform(post("/api/berth-windows/{id}/approve", id)).andExpect(status().isOk());

        // 重叠建窗 -> 409
        mvc.perform(post("/api/berth-windows").contentType(MediaType.APPLICATION_JSON)
                .content(body("API-3", 1L, "2026-09-25T02:00:00Z", "2026-09-25T04:00:00Z", "10.0", 1)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("Conflict"));

        // 超限吃水 -> 422
        mvc.perform(post("/api/berth-windows").contentType(MediaType.APPLICATION_JSON)
                .content(body("API-4", 2L, "2026-09-25T01:00:00Z", "2026-09-25T03:00:00Z", "9.9", 1)))
                .andExpect(status().isUnprocessableEntity());

        // 缺少 requestId -> 400
        mvc.perform(post("/api/berth-windows").contentType(MediaType.APPLICATION_JSON)
                .content("{\"vesselName\":\"V\",\"berthId\":1,\"startTime\":\"2026-09-25T01:00:00Z\","
                        + "\"endTime\":\"2026-09-25T03:00:00Z\",\"draft\":10.0,\"pilotsRequired\":1}"))
                .andExpect(status().isBadRequest());
    }
}
