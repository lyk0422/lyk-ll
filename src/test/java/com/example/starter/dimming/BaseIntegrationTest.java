package com.example.starter.dimming;

import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * 集成测试基类：每个用例前清空业务表、重置合成数据与可调时钟，避免顺序依赖。
 * 时钟起点固定为 2026-01-01 08:00:00（Asia/Shanghai）。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestDatabaseShutdown.class)
public abstract class BaseIntegrationTest {

    protected static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");

    @Autowired
    protected MockMvc mvc;

    @Autowired
    protected JdbcTemplate jdbc;

    @Autowired
    protected AdjustableClock clock;

    @Autowired
    protected ObjectMapper objectMapper;

    @BeforeEach
    void resetState() {
        jdbc.update("DELETE FROM idempotency_record");
        jdbc.update("DELETE FROM dimming_record");
        jdbc.update("DELETE FROM dimming_command");
        jdbc.update("DELETE FROM accident_lock_log");
        jdbc.update("DELETE FROM traffic_sample");
        jdbc.update("DELETE FROM circuit");
        jdbc.update("INSERT INTO circuit (code, name, current_level, initial_level, locked, created_at)"
                + " VALUES ('TUN-A-01', 'A隧道入口段回路1', 3, 3, 0, '2026-01-01 00:00:00')");
        jdbc.update("INSERT INTO circuit (code, name, current_level, initial_level, locked, created_at)"
                + " VALUES ('TUN-A-02', 'A隧道中段回路2', 3, 3, 0, '2026-01-01 00:00:00')");
        clock.setInstant(T0);
    }

    protected void advanceMinutes(long minutes) {
        clock.advance(Duration.ofMinutes(minutes));
    }

    protected MvcResult dim(String code, String requestId, int targetLevel, String source)
            throws Exception {
        return mvc.perform(post("/api/circuits/{code}/commands", code)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"%s","targetLevel":%d,"source":"%s"}
                                """.formatted(requestId, targetLevel, source)))
                .andReturn();
    }

    protected MvcResult telemetry(String code, String requestId, double brightness, int traffic)
            throws Exception {
        return mvc.perform(post("/api/circuits/{code}/telemetry", code)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"%s","brightness":%s,"trafficFlow":%d}
                                """.formatted(requestId, brightness, traffic)))
                .andReturn();
    }

    protected MvcResult lockAccident(String code, String requestId, String alarmId)
            throws Exception {
        return mvc.perform(post("/api/circuits/{code}/accident/lock", code)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"%s","alarmId":"%s","detail":"合成事故告警"}
                                """.formatted(requestId, alarmId)))
                .andReturn();
    }

    protected MvcResult unlockAccident(String code, String requestId, boolean confirmed)
            throws Exception {
        return mvc.perform(post("/api/circuits/{code}/accident/unlock", code)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"%s","onSiteConfirmed":%s,"detail":"现场确认"}
                                """.formatted(requestId, confirmed)))
                .andReturn();
    }

    protected JsonNode state(String code) throws Exception {
        MvcResult result = mvc.perform(get("/api/circuits/{code}", code)).andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    protected JsonNode body(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    protected int recordCount(String code) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM dimming_record WHERE circuit_code = ?", Integer.class, code);
        return count == null ? 0 : count;
    }
}
