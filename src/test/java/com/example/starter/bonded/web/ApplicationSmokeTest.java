package com.example.starter.bonded.web;

import com.example.starter.bonded.BaseIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 启动冒烟：由测试框架启动完整 Web 容器（随机端口），通过真实 HTTP 调用验证
 * 申请 -> 核注 -> 离港 -> 分批核销链路，用例结束容器随之关闭。
 */
@org.springframework.boot.test.context.SpringBootTest(
        webEnvironment = org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT)
class ApplicationSmokeTest extends BaseIntegrationTest {

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate rest;

    private String url(String path) {
        return "http://localhost:" + port + path;
    }

    @Test
    @DisplayName("真实 HTTP 冒烟：启动应用并完成申请-核注-离港-分批部分核销")
    void applicationStartsAndServesFlow() {
        String applyBody = """
                {
                  "requestId": "SMOKE-1",
                  "enterpriseId": "ENT-001",
                  "fromWarehouse": "WH-A",
                  "toWarehouse": "WH-B",
                  "items": [
                    {"hsCode": "HS-1001", "quantity": 100, "unitPrice": 10.50},
                    {"hsCode": "HS-1002", "quantity": 50, "unitPrice": 20.00}
                  ]
                }
                """;
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        ResponseEntity<String> created = rest.postForEntity(url("/api/transfers"),
                new HttpEntity<>(applyBody, headers), String.class);
        assertThat(created.getStatusCode().value()).isEqualTo(201);
        assertThat(created.getBody()).contains("APPLIED");

        assertThat(rest.postForEntity(url("/api/transfers/SMOKE-1/endorse"), null, String.class).getBody())
                .contains("ENDORSED");
        assertThat(rest.postForEntity(url("/api/transfers/SMOKE-1/depart"), null, String.class).getBody())
                .contains("IN_TRANSIT");

        ResponseEntity<String> verified = rest.postForEntity(url("/api/transfers/SMOKE-1/verify"),
                new HttpEntity<>("""
                        {"batchRequestId":"SMOKE-B1","items":[{"hsCode":"HS-1001","quantity":60}]}
                        """, headers), String.class);
        assertThat(verified.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(verified.getBody()).contains("IN_TRANSIT").contains("\"verifiedQty\":60");

        ResponseEntity<String> query = rest.exchange(url("/api/transfers/SMOKE-1"),
                HttpMethod.GET, null, String.class);
        assertThat(query.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(query.getBody()).contains("SMOKE-1", "HS-1001", "HS-1002");

        ResponseEntity<String> health = rest.getForEntity(url("/actuator/health"), String.class);
        assertThat(health.getBody()).contains("\"status\":\"UP\"");
    }
}
