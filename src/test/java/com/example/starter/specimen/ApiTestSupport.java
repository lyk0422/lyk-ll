package com.example.starter.specimen;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.math.BigDecimal;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * API 测试基类：每个用例前清空全部业务表，避免顺序依赖；提供 JSON 调用与断言辅助。
 */
@SpringBootTest
@AutoConfigureMockMvc
public abstract class ApiTestSupport {

    @Autowired
    protected MockMvc mvc;

    @Autowired
    protected ObjectMapper objectMapper;

    @Autowired
    protected JdbcTemplate jdbc;

    @BeforeEach
    void cleanTables() {
        jdbc.update("DELETE FROM custody_event");
        jdbc.update("DELETE FROM idempotency_record");
        jdbc.update("DELETE FROM assay_request");
        jdbc.update("DELETE FROM aliquot");
        jdbc.update("DELETE FROM specimen");
    }

    protected MvcResult postJson(String url, Object body) throws Exception {
        return mvc.perform(post(url)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andReturn();
    }

    protected MvcResult getJson(String url) throws Exception {
        return mvc.perform(get(url)).andReturn();
    }

    protected JsonNode body(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    protected BigDecimal decimal(JsonNode node, String field) {
        return new BigDecimal(node.get(field).asText());
    }

    protected long count(String table) {
        Long value = jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Long.class);
        return value == null ? 0 : value;
    }
}
