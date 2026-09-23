package com.example.starter.rights;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * H2 集成测试基类：每例独立清表，时钟固定在 {@link #BASE}（UTC）。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
public abstract class AbstractRightsIntegrationTest {

    /** 固定业务基准时间：2026-10-01T00:00:00Z，所有窗口均为其后的合成未来时间。 */
    protected static final Instant BASE = Instant.parse("2026-10-01T00:00:00Z");

    @Autowired
    protected MockMvc mockMvc;
    @Autowired
    protected ObjectMapper objectMapper;
    @Autowired
    protected JdbcTemplate jdbc;
    @Autowired
    protected AppClock appClock;

    @BeforeEach
    void resetEnvironment() {
        jdbc.update("DELETE FROM playback_record");
        jdbc.update("DELETE FROM schedule_item");
        jdbc.update("DELETE FROM schedule_submission");
        jdbc.update("DELETE FROM license_version");
        jdbc.update("DELETE FROM request_ledger");
        jdbc.update("DELETE FROM scope_lock");
        appClock.setClock(Clock.fixed(BASE, ZoneOffset.UTC));
    }

    protected void setClock(Instant instant) {
        appClock.setClock(Clock.fixed(instant, ZoneOffset.UTC));
    }

    protected MvcResult postJson(String uri, Object body) throws Exception {
        return mockMvc.perform(post(uri)
                .contentType("application/json")
                .content(objectMapper.writeValueAsString(body)))
                .andReturn();
    }

    protected JsonNode json(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    protected int rowCount(String table) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
        return count == null ? 0 : count;
    }
}
