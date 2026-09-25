package com.example.starter.ventilation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 真实内嵌容器冒烟测试：验证主配置 H2 自动建表/合成数据初始化与激活入口可用。
 * 容器与上下文随用例结束关闭，非常驻进程。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class VentilationSmokeTest extends AbstractVentilationIntegrationTest {

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate rest;

    @Test
    @DisplayName("真实端口：H2 初始化完成，激活接口可用并返回先阀后机指令序列")
    void applicationStartsAndActivateWorks() {
        // 合成主数据已由 schema.sql/data.sql 自动初始化
        Integer zones = jdbc.queryForObject("SELECT COUNT(1) FROM fire_zone", Integer.class);
        assertEquals(3, zones);
        Integer dampers = jdbc.queryForObject("SELECT COUNT(1) FROM device WHERE device_type='DAMPER'",
                Integer.class);
        assertEquals(5, dampers);

        Map<String, String> body = new LinkedHashMap<>();
        body.put("requestId", "smoke-act-1");
        body.put("zoneCode", "ZONE_C");
        body.put("smokeDirection", "FORWARD");
        body.put("targetCondition", "SMOKE_EXHAUST");
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        @SuppressWarnings("rawtypes")
        ResponseEntity<Map> resp = rest.postForEntity(
                "http://localhost:" + port + "/api/ventilation/activate",
                new HttpEntity<>(body, headers), Map.class);
        assertEquals(200, resp.getStatusCode().value());
        Map<?, ?> respBody = resp.getBody();
        assertNotNull(respBody);
        assertEquals("ACTIVATED", respBody.get("outcome"));

        Map<?, ?> session = (Map<?, ?>) respBody.get("session");
        Object sessionId = session.get("sessionId");
        assertNotNull(sessionId);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> commands = (List<Map<String, Object>>) session.get("commands");
        // 先阀后机：风阀 SENT，风机 PENDING
        Map<String, String> statusByDevice = new java.util.HashMap<>();
        for (Map<String, Object> c : commands) {
            statusByDevice.put((String) c.get("deviceCode"), (String) c.get("status"));
        }
        assertEquals("SENT", statusByDevice.get("D_C_1"));
        assertEquals("PENDING", statusByDevice.get("FAN_C"));

        // actuator 健康端点可用
        @SuppressWarnings("rawtypes")
        ResponseEntity<Map> health = rest.getForEntity(
                "http://localhost:" + port + "/actuator/health", Map.class);
        assertEquals(200, health.getStatusCode().value());
        assertEquals("UP", health.getBody().get("status"));
        assertTrue(port > 0);
    }
}
