package com.example.starter.bag.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * HTTP 入口端到端测试（真实 H2 + MockMvc）：覆盖写入、幂等、冲突状态码、
 * 组合查询、模拟中断、恢复与重复回放的完整对外契约。
 */
@SpringBootTest
@AutoConfigureMockMvc
class RosbagControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private NamedParameterJdbcTemplate jdbc;

    @BeforeEach
    void cleanTables() {
        jdbc.update("DELETE FROM bag_msg_index", new MapSqlParameterSource());
        jdbc.update("DELETE FROM bag_message", new MapSqlParameterSource());
        jdbc.update("DELETE FROM bag_quarantine", new MapSqlParameterSource());
        jdbc.update("DELETE FROM bag_segment", new MapSqlParameterSource());
        jdbc.update("DELETE FROM bag_play_item", new MapSqlParameterSource());
        jdbc.update("DELETE FROM bag_play", new MapSqlParameterSource());
        jdbc.update("DELETE FROM bag_topic_lock", new MapSqlParameterSource());
    }

    private String topic() {
        return "http-topic-" + UUID.randomUUID();
    }

    private String writeJson(String topic, long seq, long ts, String payload, String batchNo) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("topic", topic);
        body.put("timestampNs", ts);
        body.put("seq", seq);
        body.put("typeId", "test/Type");
        if (payload != null) {
            body.put("payloadBase64",
                    Base64.getEncoder().encodeToString(payload.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        }
        body.put("batchNo", batchNo);
        try {
            return objectMapper.writeValueAsString(body);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void writeQueryAndIdempotentRetryOverHttp() throws Exception {
        String topic = topic();
        // 首次写入 201
        mockMvc.perform(post("/api/bag/messages")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(writeJson(topic, 0L, 1000L, "hello", "b0")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.duplicate").value(false))
                .andExpect(jsonPath("$.segIndex").value(0));

        // 同批次同参重试：201 且 duplicate=true，不重复写入
        mockMvc.perform(post("/api/bag/messages")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(writeJson(topic, 0L, 1000L, "hello", "b0")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.duplicate").value(true));

        mockMvc.perform(post("/api/bag/messages")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(writeJson(topic, 1L, 1001L, "world", "b1")))
                .andExpect(status().isCreated());

        // 序列号倒退 → 409
        mockMvc.perform(post("/api/bag/messages")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(writeJson(topic, 0L, 1000L, "late", "bX")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409));

        // 参数校验失败 → 400
        mockMvc.perform(post("/api/bag/messages")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"timestampNs\":1,\"seq\":0,\"typeId\":\"t\",\"batchNo\":\"b\"}"))
                .andExpect(status().isBadRequest());

        // 组合查询：时间窗 + 序列范围
        mockMvc.perform(get("/api/bag/messages")
                        .param("topic", topic)
                        .param("startNs", "1001")
                        .param("endNs", "1001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].seq").value(1))
                .andExpect(jsonPath("$[0].payloadBase64").isNotEmpty());
    }

    @Test
    void corruptRecoverAndReplayOverHttp() throws Exception {
        String topic = topic();
        // 测试分段容量 3：写满段0(0..2) 并在段1写 3,4
        for (long seq = 0; seq < 5; seq++) {
            mockMvc.perform(post("/api/bag/messages")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(writeJson(topic, seq, 1000L + seq, "p" + seq, "b" + seq)))
                    .andExpect(status().isCreated());
        }

        // 向最新段(segIndex=1)注入 2 条中断尾消息
        mockMvc.perform(post("/api/bag/segments/corrupt")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"topic":"%s","segIndex":1,"mode":"INCOMPLETE_TAIL","tailSize":2}
                                """.formatted(topic)))
                .andExpect(status().isNoContent());

        // 恢复：保留 3,4 完整前缀，隔离 2 条尾消息
        MvcResult recoverResult = mockMvc.perform(post("/api/bag/recover"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.segments[0].segIndex").value(1))
                .andExpect(jsonPath("$.segments[0].keptCount").value(2))
                .andExpect(jsonPath("$.segments[0].quarantinedCount").value(2))
                .andExpect(jsonPath("$.segments[0].rebuiltIndexCount").value(2))
                .andExpect(jsonPath("$.segments[0].prefixVerified").value(true))
                .andReturn();
        JsonNode recovery = objectMapper.readTree(recoverResult.getResponse().getContentAsString());
        String playChecksumFirst = recovery.path("segments").path(0).path("finalStatus").asText();
        org.junit.jupiter.api.Assertions.assertEquals("OPEN", playChecksumFirst);

        // 恢复后查询回到原序 0..4
        mockMvc.perform(get("/api/bag/messages").param("topic", topic))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(5))
                .andExpect(jsonPath("$[0].seq").value(0))
                .andExpect(jsonPath("$[4].seq").value(4));

        // 首次回放
        mockMvc.perform(post("/api/bag/play")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"playKey":"key-http-1","topic":"%s","startSeq":1,"endSeq":4}
                                """.formatted(topic)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.duplicated").value(false))
                .andExpect(jsonPath("$.messageCount").value(4))
                .andExpect(jsonPath("$.messages[0].seq").value(1))
                .andExpect(jsonPath("$.messages[3].seq").value(4));

        // 重复回放：duplicated=true，playId 不变，不重复创建记录
        MvcResult second = mockMvc.perform(post("/api/bag/play")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"playKey":"key-http-1","topic":"%s","startSeq":1,"endSeq":4}
                                """.formatted(topic)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.duplicated").value(true))
                .andReturn();
        long playId = objectMapper.readTree(second.getResponse().getContentAsString()).path("playId").asLong();

        Long playRows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM bag_play WHERE play_key = :k",
                new MapSqlParameterSource("k", "key-http-1"), Long.class);
        org.junit.jupiter.api.Assertions.assertEquals(1L, playRows);
        org.junit.jupiter.api.Assertions.assertTrue(playId > 0);

        // 段校验视图可查
        mockMvc.perform(get("/api/bag/segments").param("topic", topic))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[1].checksum").isNotEmpty());
    }
}
