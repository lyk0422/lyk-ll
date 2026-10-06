package com.example.starter;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 集成测试基类：完整 Spring 上下文 + H2 内存库（MySQL 模式）+ 可控时钟。
 * 每个用例前清空业务表（保留合成台账与保护表）并复位时钟，避免顺序依赖。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestClockConfig.class)
public abstract class BaseIntegrationTest {

    @Autowired
    protected MockMvc mockMvc;

    @Autowired
    protected ObjectMapper objectMapper;

    @Autowired
    protected JdbcTemplate jdbc;

    @Autowired
    protected MutableClock clock;

    @BeforeEach
    void resetState() {
        jdbc.update("DELETE FROM release_confirmation");
        jdbc.update("DELETE FROM retest_record");
        jdbc.update("DELETE FROM power_directive");
        jdbc.update("DELETE FROM interference_event");
        jdbc.update("DELETE FROM complaint");
        jdbc.update("DELETE FROM idempotency_record");
        clock.set(TestClockConfig.BASE_TIME);
    }

    protected long transmitterIdOf(String code) {
        Long id = jdbc.queryForObject(
                "SELECT id FROM transmitter WHERE code = ?", Long.class, code);
        return id == null ? -1 : id;
    }

    protected int countRows(String table) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
        return count == null ? 0 : count;
    }

    /**
     * 通过 3 个不同监测站的有效投诉触发一个干扰事件，返回事件 id。
     * 采样时刻为当前时钟时刻，tag 用于隔离请求标识与监测站。
     */
    protected long createEventViaComplaints(String frequencyMhz, String tag) throws Exception {
        Long eventId = null;
        for (int i = 1; i <= 3; i++) {
            String sampledAt = clock.instant().toString();
            var body = new java.util.LinkedHashMap<String, Object>();
            body.put("requestId", tag + "-c" + i);
            body.put("stationId", tag + "-ST-" + i);
            body.put("frequencyMhz", frequencyMhz);
            body.put("sampledAt", sampledAt);
            body.put("fieldStrengthDbuv", "68.5");
            body.put("evidenceRef", tag + "-EV-" + i);
            var result = mockMvc.perform(org.springframework.test.web.servlet.request
                            .MockMvcRequestBuilders.post("/api/complaints")
                            .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(body)))
                    .andExpect(org.springframework.test.web.servlet.result
                            .MockMvcResultMatchers.status().isOk())
                    .andReturn();
            var json = objectMapper.readTree(result.getResponse().getContentAsString());
            if (json.get("eventCreated").asBoolean()) {
                eventId = json.get("eventId").asLong();
            }
        }
        org.assertj.core.api.Assertions.assertThat(eventId)
                .as("3 个不同监测站投诉应触发事件").isNotNull();
        return eventId;
    }

    /**
     * 初查指定事件。
     */
    protected org.springframework.test.web.servlet.ResultActions reviewEvent(long eventId,
            String requestId, String conclusion) throws Exception {
        return mockMvc.perform(org.springframework.test.web.servlet.request
                .MockMvcRequestBuilders.post("/api/events/" + eventId + "/review")
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(java.util.Map.of(
                        "requestId", requestId,
                        "reviewerId", "reviewer-1",
                        "conclusion", conclusion))));
    }
}
