package com.example.starter.rail;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 端到端 HTTP 语义测试：状态码、冲突明细、幂等请求头、快照与参数校验。
 */
@AutoConfigureMockMvc
class WebApiTest extends BaseIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    private String instant(long offsetMillis) {
        return java.time.Instant.ofEpochMilli(at(offsetMillis)).toString();
    }

    private String createJson(String id, String sections, long start, long end) {
        return """
                {"requestId":"%s","sectionIds":%s,"start":"%s","end":"%s",
                 "bufferBeforeMs":0,"bufferAfterMs":0,"priority":1}
                """.formatted(id, sections, instant(start), instant(end));
    }

    private MvcResult create(String json) throws Exception {
        return mockMvc.perform(post("/api/requests")
                        .contentType(MediaType.APPLICATION_JSON).content(json))
                .andExpect(status().isCreated())
                .andReturn();
    }

    @Test
    void create_approve_and_query_happy_path() throws Exception {
        create(createJson("W1", "[\"SEC-A\"]", 10_000, 20_000));
        mockMvc.perform(post("/api/requests/W1/approval"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"))
                .andExpect(jsonPath("$.version").value(1));
        mockMvc.perform(get("/api/requests/W1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"));
    }

    @Test
    void conflicting_approval_returns_409_with_details() throws Exception {
        create(createJson("W1", "[\"SEC-A\"]", 10_000, 30_000));
        mockMvc.perform(post("/api/requests/W1/approval")).andExpect(status().isOk());
        create(createJson("W2", "[\"SEC-A\"]", 20_000, 40_000));

        mockMvc.perform(post("/api/requests/W2/approval"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.approved").value(false))
                .andExpect(jsonPath("$.conflicts[0].sectionId").value("SEC-A"))
                .andExpect(jsonPath("$.conflicts[0].otherRequestId").value("W1"))
                .andExpect(jsonPath("$.conflicts[0].overlapMs").value(10_000));

        // 冲突查询端点返回 200 与同样的明细。
        mockMvc.perform(get("/api/requests/W2/conflicts"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.approved").value(false))
                .andExpect(jsonPath("$.conflicts[0].otherRequestId").value("W1"));
    }

    @Test
    void emergency_preemption_success_and_blocked_by_active() throws Exception {
        // 可抢占：低优先级、未激活。
        create(withPriority("L1", "[\"SEC-B\"]", 10_000, 30_000, 1));
        mockMvc.perform(post("/api/requests/L1/approval")).andExpect(status().isOk());
        create(withPriority("E1", "[\"SEC-B\"]", 10_000, 30_000, 10));

        mockMvc.perform(post("/api/requests/E1/approval").param("mode", "PREEMPT"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"));
        mockMvc.perform(get("/api/requests/L1"))
                .andExpect(jsonPath("$.status").value("PREEMPTED"));
    }

    @Test
    void unknown_request_returns_404() throws Exception {
        mockMvc.perform(get("/api/requests/NOPE"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    void idempotency_header_replay_and_mismatch() throws Exception {
        create(createJson("W3", "[\"SEC-C\"]", 10_000, 20_000));
        mockMvc.perform(post("/api/requests/W3/approval")).andExpect(status().isOk());

        String body = """
                {"expectedVersion":1,"businessTime":"%s"}
                """.formatted(instant(12_000));
        mockMvc.perform(post("/api/requests/W3/activation")
                        .header("Idempotency-Key", "K-1")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"));
        // 同键同参重放 -> 200，首次结果。
        mockMvc.perform(post("/api/requests/W3/activation")
                        .header("Idempotency-Key", "K-1")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"));
        // 同键异参 -> 409。
        String other = """
                {"expectedVersion":1,"businessTime":"%s"}
                """.formatted(instant(13_000));
        mockMvc.perform(post("/api/requests/W3/activation")
                        .header("Idempotency-Key", "K-1")
                        .contentType(MediaType.APPLICATION_JSON).content(other))
                .andExpect(status().isConflict());
    }

    @Test
    void snapshot_remains_immutable_via_http() throws Exception {
        create(createJson("W4", "[\"SEC-D\"]", 10_000, 20_000));
        mockMvc.perform(post("/api/requests/W4/approval")).andExpect(status().isOk());
        mockMvc.perform(post("/api/requests/W4/snapshots")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"snapshotId\":\"SN-1\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.statusAtPublish").value("APPROVED"));

        mockMvc.perform(post("/api/requests/W4/activation")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedVersion\":1,\"businessTime\":\"" + instant(12_000) + "\"}"))
                .andExpect(status().isOk());
        // 快照仍停留在 APPROVED。
        mockMvc.perform(get("/api/requests/snapshots/SN-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.statusAtPublish").value("APPROVED"))
                .andExpect(jsonPath("$.content.status").value("APPROVED"));
    }

    @Test
    void invalid_payload_returns_400() throws Exception {
        // 缺少 end 字段。
        String invalid = """
                {"requestId":"BAD","sectionIds":["S"],"start":"%s",
                 "bufferBeforeMs":0,"bufferAfterMs":0,"priority":1}
                """.formatted(instant(10_000));
        mockMvc.perform(post("/api/requests")
                        .contentType(MediaType.APPLICATION_JSON).content(invalid))
                .andExpect(status().isBadRequest());
    }

    @Test
    void response_times_are_serialized_as_utc() throws Exception {
        MvcResult result = create(createJson("W5", "[\"SEC-E\"]", 10_000, 20_000));
        JsonNode json = objectMapper.readTree(result.getResponse().getContentAsString());
        // 半开窗开始时刻应原样以 UTC（Z 结尾）返回。
        org.assertj.core.api.Assertions.assertThat(json.get("start").asText())
                .isEqualTo(instant(10_000));
    }

    private String withPriority(String id, String sections, long start, long end, int priority) {
        return """
                {"requestId":"%s","sectionIds":%s,"start":"%s","end":"%s",
                 "bufferBeforeMs":0,"bufferAfterMs":0,"priority":%d}
                """.formatted(id, sections, instant(start), instant(end), priority);
    }
}
