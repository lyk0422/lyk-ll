package com.example.starter.forwarder.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.example.starter.forwarder.testsupport.MutableClock;
import com.example.starter.forwarder.testsupport.RecordingUpstreamAdapter;
import com.example.starter.forwarder.upstream.UpstreamAdapter;
import com.example.starter.forwarder.support.Clock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 通过真实 HTTP 入口（MockMvc 不启动端口）验证设备写入、断网/恢复、ACK 的端到端 JSON 语义。
 */
@SpringBootTest
@AutoConfigureMockMvc
class ForwarderControllerH2Test {

    static Path tempDir;

    @DynamicPropertySource
    static void registerH2Dir(DynamicPropertyRegistry registry) throws IOException {
        tempDir = Files.createTempDirectory("forwarder-h2-web-");
        registry.add("spring.datasource.url",
                () -> "jdbc:h2:file:" + tempDir.resolve("db")
                        + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
        registry.add("spring.datasource.username", () -> "sa");
        registry.add("spring.datasource.password", () -> "");
        // 该用例验证后台调度自动补发，使用 100ms 的短间隔
        registry.add("forwarder.dispatch-interval-ms", () -> 100);
    }

    @TestConfiguration
    static class TestBeans {
        @Bean
        @Primary
        Clock mutableClock() {
            return new MutableClock();
        }

        @Bean
        @Primary
        UpstreamAdapter recordingUpstream() {
            return new RecordingUpstreamAdapter();
        }
    }

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void clean() {
        jdbcTemplate.update("DELETE FROM device_message");
        jdbcTemplate.update("DELETE FROM audit_log");
    }

    @Test
    void ingestRejectsInvalidRequest() throws Exception {
        mockMvc.perform(post("/api/forwarder/messages")
                        .contentType("application/json")
                        .content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void offlineWriteThenRecoverThenAckFlow() throws Exception {
        // 初始离线
        mockMvc.perform(get("/api/forwarder/connection"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.online").value(false));

        // 离线写入 FIFO 消息：接受并缓存
        mockMvc.perform(post("/api/forwarder/messages")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of(
                                "deviceId", "dev1",
                                "topic", "/sensor/fifo",
                                "seq", 1,
                                "priority", 5,
                                "dedupKey", "web-1",
                                "payload", "hello"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accepted").value(true))
                .andExpect(jsonPath("$.duplicated").value(false));

        // 断网状态缓存中应有 1 条待发
        mockMvc.perform(get("/api/forwarder/connection"))
                .andExpect(jsonPath("$.online").value(false))
                .andExpect(jsonPath("$.activeMessages").value(1));

        // 网络恢复
        mockMvc.perform(post("/api/forwarder/connection").param("online", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.online").value(true));

        // 等待后台调度补发（间隔 500ms 级，轮询连接观测接口）
        waitUntilActiveAcknowledgedOrInflight("web-1", 20);

        // 明确 ACK
        mockMvc.perform(post("/api/forwarder/acks")
                        .contentType("application/json")
                        .content("{\"dedupKey\":\"web-1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.matched").value(true));

        // 重复 ACK：matched=false，duplicated=true
        mockMvc.perform(post("/api/forwarder/acks")
                        .contentType("application/json")
                        .content("{\"dedupKey\":\"web-1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.matched").value(false))
                .andExpect(jsonPath("$.duplicated").value(true));
    }

    private void waitUntilActiveAcknowledgedOrInflight(String dedupKey, int maxTries) throws Exception {
        for (int i = 0; i < maxTries; i++) {
            Long count = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM device_message WHERE dedup_key=? AND status IN ('IN_FLIGHT','ACKNOWLEDGED')",
                    Long.class, dedupKey);
            if (count != null && count == 1) {
                return;
            }
            Thread.sleep(100);
        }
        throw new AssertionError("消息在超时时间内未被补发");
    }
}
