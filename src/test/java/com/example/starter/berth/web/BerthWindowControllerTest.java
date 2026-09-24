package com.example.starter.berth.web;

import com.example.starter.berth.testsupport.TestClockConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * HTTP 层测试：验证接口状态码、统一错误体与幂等重放语义。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestClockConfiguration.class)
class BerthWindowControllerTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM pilot_assignment");
        jdbc.update("DELETE FROM berth_window");
        jdbc.execute("ALTER TABLE berth_window ALTER COLUMN id RESTART WITH 1");
    }

    @Test
    void createThenGet_returnsWindow() throws Exception {
        mvc.perform(post("/api/berth-windows")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "requestId": "HTTP-001",
                                  "vesselName": "合成船-甲",
                                  "berthCode": "BERTH-A",
                                  "startAt": "2026-10-15T08:00:00Z",
                                  "endAt": "2026-10-15T10:00:00Z",
                                  "draftMeters": 5.5,
                                  "pilotsRequired": 2
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requestId").value("HTTP-001"))
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.assignedPilots.length()").value(2));

        mvc.perform(get("/api/berth-windows/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requestId").value("HTTP-001"));
    }

    @Test
    void create_sameKeyReplay_returns200SameId() throws Exception {
        String body = """
                {
                  "requestId": "HTTP-002",
                  "vesselName": "合成船-乙",
                  "berthCode": "BERTH-A",
                  "startAt": "2026-10-15T08:00:00Z",
                  "endAt": "2026-10-15T10:00:00Z",
                  "draftMeters": 5.5,
                  "pilotsRequired": 1
                }
                """;
        mvc.perform(post("/api/berth-windows").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(1));

        mvc.perform(post("/api/berth-windows").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(1));
    }

    @Test
    void create_sameKeyDifferentParams_returns409() throws Exception {
        mvc.perform(post("/api/berth-windows")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "requestId": "HTTP-003",
                                  "vesselName": "合成船-丙",
                                  "berthCode": "BERTH-A",
                                  "startAt": "2026-10-15T08:00:00Z",
                                  "endAt": "2026-10-15T10:00:00Z",
                                  "draftMeters": 5.5,
                                  "pilotsRequired": 1
                                }
                                """))
                .andExpect(status().isOk());

        mvc.perform(post("/api/berth-windows")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "requestId": "HTTP-003",
                                  "vesselName": "另一艘船",
                                  "berthCode": "BERTH-A",
                                  "startAt": "2026-10-15T08:00:00Z",
                                  "endAt": "2026-10-15T10:00:00Z",
                                  "draftMeters": 5.5,
                                  "pilotsRequired": 1
                                }
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_CONFLICT"));
    }

    @Test
    void create_conflictingWindow_returns409() throws Exception {
        mvc.perform(post("/api/berth-windows")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "requestId": "HTTP-004",
                                  "vesselName": "合成船-丁",
                                  "berthCode": "BERTH-A",
                                  "startAt": "2026-10-15T08:00:00Z",
                                  "endAt": "2026-10-15T10:00:00Z",
                                  "draftMeters": 5.5,
                                  "pilotsRequired": 1
                                }
                                """))
                .andExpect(status().isOk());

        mvc.perform(post("/api/berth-windows")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "requestId": "HTTP-005",
                                  "vesselName": "合成船-戊",
                                  "berthCode": "BERTH-A",
                                  "startAt": "2026-10-15T09:00:00Z",
                                  "endAt": "2026-10-15T11:00:00Z",
                                  "draftMeters": 5.5,
                                  "pilotsRequired": 1
                                }
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("BERTH_WINDOW_CONFLICT"));
    }

    @Test
    void create_draftExceedsLimit_returns422() throws Exception {
        mvc.perform(post("/api/berth-windows")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "requestId": "HTTP-006",
                                  "vesselName": "合成船-己",
                                  "berthCode": "BERTH-B",
                                  "startAt": "2026-10-15T08:00:00Z",
                                  "endAt": "2026-10-15T10:00:00Z",
                                  "draftMeters": 9.5,
                                  "pilotsRequired": 1
                                }
                                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("DRAFT_EXCEEDS_LIMIT"));
    }

    @Test
    void create_invalidBody_returns400() throws Exception {
        mvc.perform(post("/api/berth-windows")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "requestId": "",
                                  "vesselName": "合成船-庚",
                                  "berthCode": "BERTH-A",
                                  "startAt": "2026-10-15T08:00:00Z",
                                  "endAt": "2026-10-15T10:00:00Z",
                                  "draftMeters": -1,
                                  "pilotsRequired": 1
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void get_missingWindow_returns404() throws Exception {
        mvc.perform(get("/api/berth-windows/999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("WINDOW_NOT_FOUND"));
    }
}
