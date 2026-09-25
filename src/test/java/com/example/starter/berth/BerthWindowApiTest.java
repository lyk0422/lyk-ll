package com.example.starter.berth;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * REST API 测试：建窗 → 审批 → 生效 → 离泊主流程，以及冲突、限深、幂等错误语义。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestClockConfig.class)
class BerthWindowApiTest {

    private static final String D = "2026-10-10T";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private BerthWindowService service;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private MutableClock clock;

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM window_pilot");
        jdbc.update("DELETE FROM berth_window");
        clock.setInstant(TestClockConfig.BASE);
    }

    private static String applyBody(String requestId, String berthId, String start, String end,
                                    String draft, int pilots) {
        return """
                {
                  "requestId": "%s",
                  "vesselName": "MV-%s",
                  "berthId": "%s",
                  "startUtc": "%s",
                  "endUtc": "%s",
                  "draftMeters": %s,
                  "pilotsRequired": %d
                }
                """.formatted(requestId, requestId, berthId, start, end, draft, pilots);
    }

    private void apply(String requestId, String berthId, String start, String end,
                       String draft, int pilots) throws Exception {
        mockMvc.perform(post("/api/berth-windows")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(applyBody(requestId, berthId, start, end, draft, pilots)))
                .andExpect(status().isOk());
    }

    @Test
    void fullLifecycleViaApi() throws Exception {
        // 建窗
        mockMvc.perform(post("/api/berth-windows")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(applyBody("API-1", "BERTH-1", D + "06:00:00Z", D + "10:00:00Z", "10.5", 2)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requestId").value("API-1"))
                .andExpect(jsonPath("$.status").value("PENDING"));
        // 审批
        mockMvc.perform(post("/api/berth-windows/API-1/approve"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"))
                .andExpect(jsonPath("$.assignedPilots.length()").value(2));
        // 到达起始时刻后生效（测试时钟推进 + 手动触发定时逻辑）
        clock.setInstant(Instant.parse(D + "06:00:00Z"));
        service.activateDueWindows();
        mockMvc.perform(get("/api/berth-windows/API-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"));
        // 登记离泊
        mockMvc.perform(post("/api/berth-windows/API-1/departure"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"));
        // 终态重复操作幂等成功
        mockMvc.perform(post("/api/berth-windows/API-1/departure"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"));
    }

    @Test
    void conflictingApprovalReturns409() throws Exception {
        apply("API-A", "BERTH-1", D + "06:00:00Z", D + "10:00:00Z", "5", 0);
        apply("API-B", "BERTH-1", D + "08:00:00Z", D + "12:00:00Z", "5", 0);
        mockMvc.perform(post("/api/berth-windows/API-A/approve"))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/berth-windows/API-B/approve"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("BERTH_WINDOW_CONFLICT"));
    }

    @Test
    void draftExceedingLimitReturns422() throws Exception {
        mockMvc.perform(post("/api/berth-windows")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(applyBody("API-D", "BERTH-2", D + "06:00:00Z", D + "10:00:00Z", "9.5", 0)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("DRAFT_EXCEEDS_LIMIT"));
        mockMvc.perform(get("/api/berth-windows/API-D"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("WINDOW_NOT_FOUND"));
    }

    @Test
    void sameKeyDifferentParamsReturns409() throws Exception {
        apply("API-I", "BERTH-1", D + "06:00:00Z", D + "10:00:00Z", "5", 0);
        String mutated = """
                {
                  "requestId": "API-I",
                  "vesselName": "MV-OTHER",
                  "berthId": "BERTH-1",
                  "startUtc": "2026-10-10T06:00:00Z",
                  "endUtc": "2026-10-10T10:00:00Z",
                  "draftMeters": 5,
                  "pilotsRequired": 0
                }
                """;
        mockMvc.perform(post("/api/berth-windows")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mutated))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REQUEST_ID_CONFLICT"));
    }

    @Test
    void missingRequiredFieldReturns400() throws Exception {
        String body = """
                {
                  "vesselName": "MV-X",
                  "berthId": "BERTH-1",
                  "startUtc": "2026-10-10T06:00:00Z",
                  "endUtc": "2026-10-10T10:00:00Z",
                  "draftMeters": 5,
                  "pilotsRequired": 0
                }
                """;
        mockMvc.perform(post("/api/berth-windows")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }
}
