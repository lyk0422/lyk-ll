package com.example.starter.web;

import com.example.starter.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 经真实 HTTP 入口（MockMvc + H2）验证登记、保全、两阶段清除、清单查询全链路及错误状态码。
 */
@AutoConfigureMockMvc
class EvidenceApiEndToEndTest extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    private String b64(String content) {
        return Base64.getEncoder().encodeToString(content.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void fullLifecycleThroughHttp() throws Exception {
        // 1. 登记两条合成证据：E1 已到期，E2 未到期。
        mockMvc.perform(post("/api/evidence").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"evidenceId":"E1","contentBase64":"%s","collectedAt":"2026-01-01T00:00:00Z","expiresAt":"2026-01-01T00:10:00Z"}
                                """.formatted(b64("synthetic-body-1"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("RETAINED"))
                .andExpect(jsonPath("$.sha256").isString())
                .andExpect(jsonPath("$.expiresAt").value("2026-01-01T00:10:00Z"));

        mockMvc.perform(post("/api/evidence").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"evidenceId":"E2","contentBase64":"%s","collectedAt":"2026-01-01T00:00:00Z","expiresAt":"2026-02-01T00:00:00Z"}
                                """.formatted(b64("synthetic-body-2"))))
                .andExpect(status().isCreated());

        // 2. 同 ID 同摘要重放 200（幂等），异摘要 409。
        mockMvc.perform(post("/api/evidence").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"evidenceId":"E1","contentBase64":"%s","collectedAt":"2026-01-01T00:00:00Z","expiresAt":"2026-01-01T00:10:00Z"}
                                """.formatted(b64("synthetic-body-1"))))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/evidence").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"evidenceId":"E1","contentBase64":"%s","collectedAt":"2026-01-01T00:00:00Z","expiresAt":"2026-01-01T00:10:00Z"}
                                """.formatted(b64("tampered-body"))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409));

        // 3. 证据状态与保管链查询。
        mockMvc.perform(get("/api/evidence/E1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.evidenceId").value("E1"));
        mockMvc.perform(get("/api/evidence/MISSING"))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/evidence/E1/custody"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].eventType").value("REGISTERED"));

        // 4. 创建法律保全（显式证据集合）。
        MvcResult holdResult = mockMvc.perform(post("/api/holds").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"caseId":"CASE-7","evidenceIds":["E1"],"requestId":"HOLD-REQ-1","expectedVersion":0}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.version").value(1))
                .andReturn();

        // 5. 活动保全阻止清除：标记批次候选为空。
        MvcResult blockedMark = mockMvc.perform(post("/api/purges/mark").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"MARK-1","cutoffTime":"2026-01-02T00:00:00Z","expectedVersion":1}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.items").isArray())
                .andExpect(jsonPath("$.items.length()").value(0))
                .andReturn();

        // 6. 同键异参（不同截止时间）409。
        mockMvc.perform(post("/api/purges/mark").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"MARK-1","cutoffTime":"2026-01-03T00:00:00Z","expectedVersion":1}
                                """))
                .andExpect(status().isConflict());

        // 7. 解除保全后标记 E1，E2 因未到期仍被排除。
        Object holdIdValue = com.jayway.jsonpath.JsonPath
                .read(holdResult.getResponse().getContentAsString(), "$.holdId");
        String holdId = String.valueOf(holdIdValue);
        mockMvc.perform(post("/api/holds/release").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"holdId":%s,"requestId":"REL-1","expectedVersion":1}
                                """.formatted(holdId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RELEASED"))
                .andExpect(jsonPath("$.version").value(2));

        MvcResult markResult = mockMvc.perform(post("/api/purges/mark").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"MARK-2","cutoffTime":"2026-01-02T00:00:00Z","expectedVersion":2}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("MARKED"))
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].evidenceId").value("E1"))
                .andReturn();
        String batchId = com.jayway.jsonpath.JsonPath
                .read(markResult.getResponse().getContentAsString(), "$.batchId");

        // 8. 最终确认：冻结不可变清单，E1 置 PURGED。
        mockMvc.perform(post("/api/purges/confirm").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"batchId":"%s"}
                                """.formatted(batchId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PURGED"))
                .andExpect(jsonPath("$.entries.length()").value(1))
                .andExpect(jsonPath("$.entries[0].evidenceId").value("E1"))
                .andExpect(jsonPath("$.entries[0].sha256").isString());

        // 9. 重复确认返回首次冻结清单；批次/清单查询一致。
        mockMvc.perform(post("/api/purges/confirm").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"batchId":"%s"}
                                """.formatted(batchId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entries.length()").value(1));
        mockMvc.perform(get("/api/purges/batches/" + batchId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PURGED"));
        mockMvc.perform(get("/api/purges/batches/" + batchId + "/manifest"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entries[0].evidenceId").value("E1"));

        // 10. E2 未到期，仍 RETAINED；E1 已 PURGED。
        mockMvc.perform(get("/api/evidence/E2"))
                .andExpect(jsonPath("$.status").value("RETAINED"));
        mockMvc.perform(get("/api/evidence/E1"))
                .andExpect(jsonPath("$.status").value("PURGED"));

        // 11. 清除提交后再建保全整笔失败 409。
        mockMvc.perform(post("/api/holds").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"caseId":"CASE-LATE","evidenceIds":["E1"],"requestId":"HOLD-LATE","expectedVersion":2}
                                """))
                .andExpect(status().isConflict());

        // 12. 请求体校验失败 400。
        mockMvc.perform(post("/api/evidence").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"evidenceId":"","contentBase64":"%s","expiresAt":"2026-01-01T00:10:00Z"}
                                """.formatted(b64("x"))))
                .andExpect(status().isBadRequest());
    }
}
