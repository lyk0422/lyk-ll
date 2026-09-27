package com.example.rebate.testsupport;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.rebate.support.TimeService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * API/H2 集成测试基类：每个用例前清空全部业务表并重置时钟，保证用例间无顺序依赖。
 */
@SpringBootTest(classes = com.example.starter.StarterApplication.class)
@AutoConfigureMockMvc
public abstract class BaseApiTest {

    private static final List<String> TABLES = List.of(
            "t_idempotent_record",
            "t_reversal_receipt",
            "t_reversal",
            "t_reversal_quota",
            "t_late_event",
            "t_treasury_writeoff",
            "t_doubt",
            "t_check_detail",
            "t_invoice_registry",
            "t_declaration_registry",
            "t_batch_document",
            "t_declaration_batch",
            "t_input_invoice",
            "t_customs_declaration",
            "t_rate_library");

    @Autowired
    protected MockMvc mvc;

    @Autowired
    protected ObjectMapper objectMapper;

    @Autowired
    protected JdbcTemplate jdbc;

    @Autowired
    protected TimeService timeService;

    @org.junit.jupiter.api.BeforeEach
    void cleanTables() {
        jdbc.update("SET REFERENTIAL_INTEGRITY FALSE");
        for (String table : TABLES) {
            jdbc.update("DELETE FROM " + table);
        }
        jdbc.update("SET REFERENTIAL_INTEGRITY TRUE");
        timeService.reset();
    }

    /**
     * POST JSON 并断言期望状态码，返回解析后的响应体。
     */
    protected JsonNode postJson(String path, String json, int expectedStatus) throws Exception {
        MvcResult result = mvc.perform(post(path)
                        .contentType("application/json")
                        .content(json))
                .andExpect(status().is(expectedStatus))
                .andReturn();
        String body = result.getResponse().getContentAsString();
        return body.isBlank() ? null : objectMapper.readTree(body);
    }

    /**
     * POST JSON，不预设状态码，返回原始结果（并发测试用）。
     */
    protected MvcResult postRaw(String path, String json) throws Exception {
        return mvc.perform(post(path)
                .contentType("application/json")
                .content(json)).andReturn();
    }

    /**
     * GET 并断言期望状态码，返回解析后的响应体。
     */
    protected JsonNode getJson(String path, int expectedStatus) throws Exception {
        MvcResult result = mvc.perform(get(path))
                .andExpect(status().is(expectedStatus))
                .andReturn();
        String body = result.getResponse().getContentAsString();
        return body.isBlank() ? null : objectMapper.readTree(body);
    }

    /**
     * 录入 V2025/V2026 两版文库。
     */
    protected void seedLibrary() throws Exception {
        postJson("/api/library", TestFixtures.libraryV2025("req-lib-2025"), 200);
        postJson("/api/library", TestFixtures.libraryV2026("req-lib-2026"), 200);
    }
}
