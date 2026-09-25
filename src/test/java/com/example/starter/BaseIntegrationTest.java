package com.example.starter;

import com.example.starter.support.BusinessClock;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 测试基类：随机端口启动完整 Web 上下文（H2 独立内存库 repair_test），
 * 每个用例前清理业务表并复位班组/馈线快照与工单序号。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public abstract class BaseIntegrationTest {

    protected static final Instant T0 = Instant.parse("2026-09-26T02:00:00Z");

    @LocalServerPort
    protected int port;

    @Autowired
    protected TestRestTemplate rest;

    @Autowired
    protected JdbcTemplate jdbc;

    @Autowired
    protected ObjectMapper objectMapper;

    @Autowired
    protected BusinessClock clock;

    @BeforeEach
    void cleanUp() {
        jdbc.update("DELETE FROM order_event");
        jdbc.update("DELETE FROM load_recovery");
        jdbc.update("DELETE FROM restore_confirm");
        jdbc.update("DELETE FROM generator_truck");
        jdbc.update("DELETE FROM crew_execution");
        jdbc.update("DELETE FROM idempotent_request");
        jdbc.update("DELETE FROM fault_order");
        jdbc.update("UPDATE crew SET status = 'IDLE'");
        jdbc.update("UPDATE feeder SET status = 'NORMAL'");
        jdbc.update("ALTER SEQUENCE seq_order_no RESTART WITH 1");
        // 默认固定时钟到 T0（上海时间 10:00），各用例再按需拨动
        clock.freezeAt(T0.toEpochMilli());
    }

    protected String url(String path) {
        return "http://localhost:" + port + "/api/repair" + path;
    }

    protected HttpHeaders jsonHeaders() {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        return h;
    }

    protected HttpEntity<String> json(Object body) {
        try {
            return new HttpEntity<>(objectMapper.writeValueAsString(body), jsonHeaders());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    protected ResponseEntity<Map<String, Object>> post(String path, Object body) {
        return (ResponseEntity) rest.postForEntity(url(path), json(body), Map.class);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    protected ResponseEntity<Map<String, Object>> get(String path) {
        return (ResponseEntity) rest.getForEntity(url(path), Map.class);
    }

    protected Map<String, Object> require2xx(ResponseEntity<Map<String, Object>> resp) {
        if (!resp.getStatusCode().is2xxSuccessful()) {
            throw new AssertionError("期望 2xx，实际 " + resp.getStatusCode() + "，响应体=" + resp.getBody());
        }
        return resp.getBody();
    }

    protected String orderNo(Map<String, Object> resp) {
        Object no = resp.get("orderNo");
        if (no == null) {
            throw new AssertionError("响应缺少 orderNo: " + resp);
        }
        return no.toString();
    }

    protected int countSql(String sql, Object... args) {
        Integer n = jdbc.queryForObject(sql, Integer.class, args);
        return n == null ? 0 : n;
    }
}
