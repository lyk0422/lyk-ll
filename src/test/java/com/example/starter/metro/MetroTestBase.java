package com.example.starter.metro;

import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 集成测试基类：每个用例前清空业务表（里程/站点等基础数据保留），
 * 提供进出闸、封账与查询的请求辅助方法。
 */
@SpringBootTest
@AutoConfigureMockMvc
public abstract class MetroTestBase {

    @Autowired
    protected MockMvc mockMvc;

    @Autowired
    protected ObjectMapper objectMapper;

    @Autowired
    protected JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanBusinessTables() {
        jdbcTemplate.update("DELETE FROM clearing_detail");
        jdbcTemplate.update("DELETE FROM diff_record");
        jdbcTemplate.update("DELETE FROM settlement_batch");
        jdbcTemplate.update("DELETE FROM trip");
        jdbcTemplate.update("DELETE FROM metro_transaction");
        jdbcTemplate.update("DELETE FROM idempotency_record");
    }

    /** 发起进闸并断言 200，返回响应 JSON。 */
    protected JsonNode entry(String requestId, String txNo, String ticketId, String station,
            String occurredAt) throws Exception {
        return postOk("/api/metro/entry", Map.of(
                "requestId", requestId, "txNo", txNo, "ticketId", ticketId,
                "station", station, "occurredAt", occurredAt));
    }

    /** 发起出闸并断言 200，返回响应 JSON。 */
    protected JsonNode exit(String requestId, String txNo, String ticketId, String station,
            String occurredAt) throws Exception {
        return postOk("/api/metro/exit", Map.of(
                "requestId", requestId, "txNo", txNo, "ticketId", ticketId,
                "station", station, "occurredAt", occurredAt));
    }

    /** 发起日结封账并断言 200，返回响应 JSON。 */
    protected JsonNode seal(String requestId, String batchDate) throws Exception {
        return postOk("/api/metro/settlement/seal",
                Map.of("requestId", requestId, "batchDate", batchDate));
    }

    protected JsonNode postOk(String url, Map<String, String> body) throws Exception {
        MvcResult result = mockMvc.perform(MockMvcRequestBuilders.post(url)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    protected MvcResult postRaw(String url, Map<String, String> body) throws Exception {
        return mockMvc.perform(MockMvcRequestBuilders.post(url)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)))
                .andReturn();
    }

    protected JsonNode getOk(String url) throws Exception {
        MvcResult result = mockMvc.perform(MockMvcRequestBuilders.get(url))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    protected long countRows(String table) {
        Long count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + table, Long.class);
        return count == null ? 0 : count;
    }
}
