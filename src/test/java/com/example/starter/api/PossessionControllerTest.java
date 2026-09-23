package com.example.starter.api;

import com.fasterxml.jackson.databind.JsonNode;
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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 封锁窗 REST 入口端到端测试（真实 Spring MVC + H2）：
 * 覆盖普通审批、冲突 409、紧急抢占、幂等重放/异参 409、快照与 404。
 */
@SpringBootTest
@AutoConfigureMockMvc
class PossessionControllerTest {

    private static final String T0 = "2026-10-01T00:00:00Z";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM possession_event");
        jdbc.update("DELETE FROM possession_section");
        jdbc.update("DELETE FROM possession_request");
        jdbc.update("DELETE FROM rail_section");
        jdbc.update("DELETE FROM operation_ledger");
        jdbc.update("DELETE FROM plan_snapshot");
    }

    private String createJson(String id, String sections, String start, String end,
                              long pre, long post, int priority) {
        return """
                {"requestId":"%s","sectionIds":[%s],"start":"%s","end":"%s",
                 "preBufferSeconds":%d,"postBufferSeconds":%d,"priority":%d,
                 "businessTime":"2026-09-30T23:00:00Z"}
                """.formatted(id, sections, start, end, pre, post, priority);
    }

    private String opJson(int version, String businessTime) {
        return "{\"expectedVersion\":%d,\"businessTime\":\"%s\"}".formatted(version, businessTime);
    }

    @Test
    void fullHttpFlow_approveConflictPreemptIdempotencySnapshot() throws Exception {
        // 1) 创建 A 并审批成功
        mockMvc.perform(post("/api/possession/requests")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createJson("A", "\"S1\"", T0, "2026-10-01T02:00:00Z", 0, 0, 10)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.bufferedStart").value(T0));

        mockMvc.perform(post("/api/possession/requests/A/approve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(opJson(1, "2026-10-01T03:00:00Z")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"))
                .andExpect(jsonPath("$.version").value(2))
                .andExpect(jsonPath("$.replayed").value(false));

        // 2) 冲突的普通申请：查询 conflict=true，审批 409 且带排序后的冲突明细
        mockMvc.perform(post("/api/possession/requests")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createJson("B", "\"S1\"", "2026-10-01T01:00:00Z",
                                "2026-10-01T03:00:00Z", 0, 0, 10)))
                .andExpect(status().isCreated());

        mockMvc.perform(get("/api/possession/requests/B/conflicts"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.conflict").value(true))
                .andExpect(jsonPath("$.conflicts[0].sectionId").value("S1"))
                .andExpect(jsonPath("$.conflicts[0].conflictingRequestId").value("A"));

        mockMvc.perform(post("/api/possession/requests/B/approve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(opJson(1, "2026-10-01T03:01:00Z")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFLICT"))
                .andExpect(jsonPath("$.conflicts[0].conflictingRequestId").value("A"));

        // 3) 紧急申请抢占：A 变 PREEMPTED，E 审批成功
        mockMvc.perform(post("/api/possession/requests")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createJson("E", "\"S1\"", "2026-10-01T01:00:00Z",
                                "2026-10-01T03:00:00Z", 0, 0, 20)))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/possession/requests/E/approve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(opJson(1, "2026-10-01T03:02:00Z")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"))
                .andExpect(jsonPath("$.preemptedRequestIds[0]").value("A"));

        mockMvc.perform(get("/api/possession/requests/A"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PREEMPTED"));

        // 4) 激活 E：错版本 422，正确版本成功；同参重放 replayed=true
        mockMvc.perform(post("/api/possession/requests/E/activate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(opJson(9, "2026-10-01T03:03:00Z")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("BUSINESS_RULE"));

        mockMvc.perform(post("/api/possession/requests/E/activate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(opJson(2, "2026-10-01T03:03:00Z")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.version").value(3));

        mockMvc.perform(post("/api/possession/requests/E/activate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(opJson(2, "2026-10-01T03:03:00Z")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.replayed").value(true))
                .andExpect(jsonPath("$.status").value("ACTIVE"));

        // 异参复用请求键 409
        mockMvc.perform(post("/api/possession/requests/E/activate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(opJson(2, "2026-10-01T03:09:00Z")))
                .andExpect(status().isConflict());

        // 5) 完成后发布快照，快照内容冻结为完成时状态
        mockMvc.perform(post("/api/possession/requests/E/complete")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(opJson(3, "2026-10-01T04:00:00Z")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"));

        MvcResult snapResult = mockMvc.perform(post("/api/possession/snapshots")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"businessTime\":\"2026-10-01T04:05:00Z\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.requests[?(@.requestId=='E')].status",
                        org.hamcrest.Matchers.hasItem("COMPLETED")))
                .andReturn();
        JsonNode snapBody = objectMapper.readTree(snapResult.getResponse().getContentAsString());
        String snapshotId = snapBody.get("snapshotId").asText();

        mockMvc.perform(get("/api/possession/snapshots/" + snapshotId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.snapshotId").value(snapshotId));

        mockMvc.perform(get("/api/possession/snapshots/snap-not-exist"))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/possession/requests/NOPE"))
                .andExpect(status().isNotFound());
    }

    @Test
    void validationFailure_returns400() throws Exception {
        mockMvc.perform(post("/api/possession/requests")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"","sectionIds":[],"start":"%s",
                                 "end":"2026-10-01T01:00:00Z","preBufferSeconds":0,
                                 "postBufferSeconds":0,"priority":10,
                                 "businessTime":"2026-09-30T23:00:00Z"}
                                """.formatted(T0)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void bufferReflectedInOccupationWindow() throws Exception {
        mockMvc.perform(post("/api/possession/requests")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createJson("A", "\"S9\"", T0, "2026-10-01T02:00:00Z", 600, 900, 10)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.bufferedStart").value("2026-09-30T23:50:00Z"))
                .andExpect(jsonPath("$.bufferedEnd").value("2026-10-01T02:15:00Z"));
    }
}
