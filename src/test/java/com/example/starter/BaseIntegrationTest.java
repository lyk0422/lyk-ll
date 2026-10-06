package com.example.starter;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * 集成测试基类：H2 内存库（MySQL 兼容模式）、可控时钟与合成台账夹具。
 * 每个用例前清空业务表并重建合成台账，避免用例间顺序依赖。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestClockConfiguration.class)
public abstract class BaseIntegrationTest {

    @Autowired
    protected MockMvc mvc;

    @Autowired
    protected JdbcTemplate jdbc;

    @Autowired
    protected MutableClock clock;

    @Autowired
    protected ObjectMapper om;

    @BeforeEach
    void resetFixtures() {
        jdbc.update("DELETE FROM retest_record");
        jdbc.update("DELETE FROM power_directive_revision");
        jdbc.update("DELETE FROM power_directive");
        jdbc.update("DELETE FROM complaint");
        jdbc.update("DELETE FROM interference_event");
        jdbc.update("DELETE FROM frequency_guard");
        jdbc.update("DELETE FROM transmitter_channel");
        jdbc.update("DELETE FROM protection_limit");
        jdbc.update("DELETE FROM transmitter");
        jdbc.update("INSERT INTO transmitter (id, code, full_power_w) VALUES (1, 'TX-ALPHA', 10000), (2, 'TX-BRAVO', 5000)");
        jdbc.update("INSERT INTO transmitter_channel (transmitter_id, frequency_mhz)"
                + " VALUES (1, 98.100), (1, 98.500), (2, 101.700)");
        jdbc.update("INSERT INTO protection_limit (frequency_mhz, limit_w)"
                + " VALUES (98.100, 3000), (98.500, 2000), (101.700, 1500)");
        clock.setInstant(TestClockConfiguration.T0);
    }

    /**
     * 发起投诉登记请求。
     *
     * @return 响应 JSON
     */
    protected JsonNode postComplaint(String requestId, String station, String frequency, Instant sampledAt,
                                     String fieldStrength, String evidenceId) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", requestId);
        body.put("stationCode", station);
        body.put("frequencyMhz", new BigDecimal(frequency));
        body.put("sampledAt", sampledAt.toString());
        body.put("fieldStrengthDbuv", new BigDecimal(fieldStrength));
        if (evidenceId != null) {
            body.put("evidenceId", evidenceId);
        }
        MvcResult result = mvc.perform(post("/api/complaints")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(body)))
                .andReturn();
        return om.readTree(result.getResponse().getContentAsString());
    }

    /**
     * 以三个不同监测站在给定采样时刻登记投诉，恰第 3 个触发干扰事件。
     *
     * @return 触发的事件主键
     */
    protected long triggerEvent(String frequency, Instant sampledAt) throws Exception {
        postComplaint("req-" + frequency + "-s1", "ST-1", frequency, sampledAt, "72.5", "EV-1");
        postComplaint("req-" + frequency + "-s2", "ST-2", frequency, sampledAt.plusSeconds(60), "73.0", "EV-2");
        JsonNode third = postComplaint("req-" + frequency + "-s3", "ST-3", frequency,
                sampledAt.plusSeconds(120), "74.1", "EV-3");
        return third.get("eventId").asLong();
    }

    /**
     * 初查处置请求。
     */
    protected JsonNode postInvestigation(long eventId, String requestId, boolean confirmed, String note)
            throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", requestId);
        body.put("confirmed", confirmed);
        body.put("note", note);
        MvcResult result = mvc.perform(post("/api/events/{id}/investigation", eventId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(body)))
                .andReturn();
        return om.readTree(result.getResponse().getContentAsString());
    }

    protected int countRows(String table) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
        return count == null ? 0 : count;
    }
}
