package com.example.starter.web;

import com.example.starter.repo.AuditRepository;
import com.example.starter.repo.DeviceMessageRepository;
import com.example.starter.testsupport.MutableTestClock;
import com.example.starter.testsupport.TestClockConfig;
import com.example.starter.upstream.SyntheticUpstreamClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 真实 HTTP 入口端到端测试（随机端口 + 独立临时目录 H2 文件库）：
 * 覆盖配置策略、离线写入丢弃/缓存、恢复补发、ACK、状态查询与审计查询。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(TestClockConfig.class)
class ForwarderHttpE2ETest {

    @TempDir
    static Path tempDir;

    @DynamicPropertySource
    static void dataSource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () ->
                "jdbc:h2:file:" + tempDir.resolve("forwarder-http")
                        + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE");
    }

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private DeviceMessageRepository messageRepository;

    @Autowired
    private AuditRepository auditRepository;

    @Autowired
    private SyntheticUpstreamClient upstream;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private MutableTestClock clock;

    @BeforeEach
    void cleanTables() {
        jdbcTemplate.update("DELETE FROM device_message");
        jdbcTemplate.update("DELETE FROM audit_record");
        jdbcTemplate.update("DELETE FROM topic_config");
        upstream.setOnline(true);
        upstream.setFailureRate(0);
        upstream.resetStats();
    }

    private String url(String path) {
        return "http://localhost:" + port + "/api/forwarder" + path;
    }

    private HttpHeaders jsonHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    @Test
    void fullOfflineRecoverAckCycleOverHttp() {
        // 1. 配置 DROP 与 LATEST 两个 Topic
        restTemplate.postForEntity(url("/topics"), new HttpEntity<>(
                "{\"topic\":\"/cmd\",\"policy\":\"DROP\"}", jsonHeaders()), String.class);
        restTemplate.postForEntity(url("/topics"), new HttpEntity<>(
                "{\"topic\":\"/state\",\"policy\":\"LATEST\"}", jsonHeaders()), String.class);

        // 2. 断网
        restTemplate.postForEntity(url("/network"), new HttpEntity<>(
                "{\"online\":false}", jsonHeaders()), String.class);

        // DROP Topic 的消息被丢弃
        String dropped = restTemplate.postForObject(url("/messages"), new HttpEntity<>(
                "{\"deviceId\":\"d1\",\"topic\":\"/cmd\",\"seq\":1,\"priority\":5,"
                        + "\"dedupKey\":\"k-cmd-1\",\"payload\":\"go\"}",
                jsonHeaders()), String.class);
        assertThat(dropped).contains("DROPPED");

        // LATEST Topic 连续两条只留最新
        restTemplate.postForObject(url("/messages"), new HttpEntity<>(
                "{\"deviceId\":\"d1\",\"topic\":\"/state\",\"seq\":1,\"priority\":5,"
                        + "\"dedupKey\":\"k-s-1\",\"payload\":\"v1\"}",
                jsonHeaders()), String.class);
        String secondState = restTemplate.postForObject(url("/messages"), new HttpEntity<>(
                "{\"deviceId\":\"d1\",\"topic\":\"/state\",\"seq\":2,\"priority\":5,"
                        + "\"dedupKey\":\"k-s-2\",\"payload\":\"v2\"}",
                jsonHeaders()), String.class);
        assertThat(secondState).contains("ACCEPTED");

        // FIFO Topic（未配置，默认 FIFO）缓存两条
        restTemplate.postForObject(url("/messages"), new HttpEntity<>(
                "{\"deviceId\":\"d2\",\"topic\":\"/log\",\"seq\":1,\"priority\":1,"
                        + "\"dedupKey\":\"k-f-1\",\"payload\":\"a\"}",
                jsonHeaders()), String.class);
        restTemplate.postForObject(url("/messages"), new HttpEntity<>(
                "{\"deviceId\":\"d2\",\"topic\":\"/log\",\"seq\":2,\"priority\":1,"
                        + "\"dedupKey\":\"k-f-2\",\"payload\":\"b\"}",
                jsonHeaders()), String.class);

        // 状态：待发 3（LATEST 1 + FIFO 2）
        String offlineStatus = restTemplate.getForObject(url("/status"), String.class);
        assertThat(offlineStatus).contains("\"online\":false").contains("\"pending\":3");

        // 3. 恢复联网，自动补发
        restTemplate.postForEntity(url("/network"), new HttpEntity<>(
                "{\"online\":true}", jsonHeaders()), String.class);

        String onlineStatus = restTemplate.getForObject(url("/status"), String.class);
        assertThat(onlineStatus).contains("\"online\":true").contains("\"inFlight\":3")
                .contains("\"pending\":0");

        // 4. ACK 确认一条，状态计数更新
        String ack = restTemplate.postForObject(url("/acks"), new HttpEntity<>(
                "{\"dedupKey\":\"k-s-2\"}", jsonHeaders()), String.class);
        assertThat(ack).contains("DELIVERED");

        // 重复 ACK 被忽略
        String duplicateAck = restTemplate.postForObject(url("/acks"), new HttpEntity<>(
                "{\"dedupKey\":\"k-s-2\"}", jsonHeaders()), String.class);
        assertThat(duplicateAck).contains("IGNORED");

        String finalStatus = restTemplate.getForObject(url("/status"), String.class);
        assertThat(finalStatus).contains("\"delivered\":1");

        // 5. 审计：DROP 与 OVERWRITE 均有据可查
        String dropAudits = restTemplate.getForObject(url("/audits?type=DROP_POLICY"),
                String.class);
        assertThat(dropAudits).contains("k-cmd-1");
        String overwriteAudits = restTemplate.getForObject(
                url("/audits?type=OVERWRITE"), String.class);
        assertThat(overwriteAudits).contains("k-s-1");

        // 存活消息明细可查
        String messages = restTemplate.getForObject(url("/messages"), String.class);
        assertThat(messages).contains("k-f-1").contains("k-f-2").doesNotContain("k-s-2");
    }

    @Test
    void invalidMessageRequestIsRejectedWith400() {
        var response = restTemplate.postForEntity(url("/messages"), new HttpEntity<>(
                "{\"deviceId\":\"\",\"topic\":\"/t\",\"seq\":1,\"priority\":5,"
                        + "\"dedupKey\":\"k\"}", jsonHeaders()), String.class);
        assertThat(response.getStatusCode().is4xxClientError()).isTrue();
    }

    @Test
    void boundedPolicyOverHttpIsParsedAndApplied() {
        restTemplate.postForEntity(url("/topics"), new HttpEntity<>(
                "{\"topic\":\"/b\",\"policy\":\"BOUNDED(2)\"}", jsonHeaders()), String.class);
        restTemplate.postForEntity(url("/network"), new HttpEntity<>(
                "{\"online\":false}", jsonHeaders()), String.class);
        for (int i = 1; i <= 3; i++) {
            restTemplate.postForObject(url("/messages"), new HttpEntity<>(
                    "{\"deviceId\":\"d1\",\"topic\":\"/b\",\"seq\":" + i + ",\"priority\":5,"
                            + "\"dedupKey\":\"kb-" + i + "\",\"payload\":\"x\"}",
                    jsonHeaders()), String.class);
        }
        String status = restTemplate.getForObject(url("/status"), String.class);
        assertThat(status).contains("\"pending\":2");
        String overflow = restTemplate.getForObject(
                url("/audits?type=BOUNDED_OVERFLOW"), String.class);
        assertThat(overflow).contains("kb-1");
    }
}
