package com.example.starter.media;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 真实随机端口启动冒烟：验证无需外部数据库即可启动、自动建表并通过真实 HTTP 提供服务。
 * 应用上下文由测试框架管理，用例结束即关闭。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class LocalStartupSmokeTest {

    @LocalServerPort
    private int port;
    @Autowired
    private TestRestTemplate restTemplate;
    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void cleanUp() {
        jdbc.update("DELETE FROM play_schedule_item");
        jdbc.update("DELETE FROM play_schedule");
        jdbc.update("DELETE FROM rights_grant_version");
        jdbc.update("DELETE FROM rights_grant");
        jdbc.update("DELETE FROM idempotent_request");
        jdbc.update("DELETE FROM rights_key_lock");
    }

    @Test
    void bootsWithEmbeddedH2AndServesFullFlowOverHttp() {
        String base = "http://localhost:" + port;

        // 健康检查端点可用
        ResponseEntity<String> health = restTemplate.getForEntity(base + "/actuator/health", String.class);
        assertEquals(200, health.getStatusCode().value());
        assertTrue(health.getBody() != null && health.getBody().contains("UP"));

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        // 创建授权
        String grantReq = """
                {
                  "requestId": "%s",
                  "assetId": "asset-smoke", "region": "CN", "platform": "WEB",
                  "windowStart": "2026-10-01T00:00:00Z", "windowEnd": "2026-10-01T04:00:00Z",
                  "mode": "EXCLUSIVE"
                }
                """.formatted(UUID.randomUUID());
        ResponseEntity<Map> grantResp = restTemplate.exchange(base + "/api/media/grants",
                HttpMethod.POST, new HttpEntity<>(grantReq, headers), Map.class);
        assertEquals(201, grantResp.getStatusCode().value());
        Object grantId = grantResp.getBody().get("grantId");
        assertNotNull(grantId);

        // 历史查询
        ResponseEntity<String> history = restTemplate.getForEntity(
                base + "/api/media/grants/" + grantId + "/history", String.class);
        assertEquals(200, history.getStatusCode().value());
        assertTrue(history.getBody().contains("\"versionNo\":1"));

        // 可播校验（半开端点）
        ResponseEntity<Map> check = restTemplate.exchange(base + "/api/media/playback/check",
                HttpMethod.POST,
                new HttpEntity<>("""
                        {"assetId": "asset-smoke", "region": "CN", "platform": "WEB",
                         "at": "2026-10-01T04:00:00Z"}
                        """, headers),
                Map.class);
        assertEquals(Boolean.FALSE, check.getBody().get("playable"));
    }
}
