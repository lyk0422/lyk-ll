package com.example.starter.tf2.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 真实 HTTP 入口 + H2 的全栈测试：验证状态码、errorCode、环候选路径与查询 JSON。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class Tf2ControllerIntegrationTest {

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @BeforeEach
    void clean() {
        jdbcTemplate.update("DELETE FROM tf_transform_sample");
    }

    @Test
    void publishStaticThenQueryAcrossChain() throws Exception {
        ResponseEntity<String> first = postTransform(staticReq("world", "base", 1, 0, 0, "s1"));
        assertEquals(HttpStatus.CREATED, first.getStatusCode());

        ResponseEntity<String> second = postTransform(staticReq("base", "tool", 0, 2, 0, "s2"));
        assertEquals(HttpStatus.CREATED, second.getStatusCode());

        ResponseEntity<String> query = restTemplate.getForEntity(
                url("/api/transform?source=world&target=tool&stampNanos=0"), String.class);
        assertEquals(HttpStatus.OK, query.getStatusCode());

        JsonNode body = objectMapper.readTree(query.getBody());
        assertEquals("world", body.get("sourceFrame").asText());
        assertEquals("tool", body.get("targetFrame").asText());
        assertEquals(1.0, body.get("tx").asDouble(), 1e-9);
        assertEquals(2.0, body.get("ty").asDouble(), 1e-9);
        assertEquals("world", body.get("path").get(0).asText());
        assertEquals("base", body.get("path").get(1).asText());
        assertEquals("tool", body.get("path").get(2).asText());
    }

    @Test
    void cycleReturns422WithCompletePath() throws Exception {
        postTransform(dynamicReq("A", "B", 100L, "s1"));
        postTransform(dynamicReq("B", "C", 100L, "s2"));

        ResponseEntity<String> response = postTransform(dynamicReq("C", "A", 100L, "s3"));
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, response.getStatusCode());

        JsonNode body = objectMapper.readTree(response.getBody());
        assertEquals("CYCLE_DETECTED", body.get("errorCode").asText());
        JsonNode cycle = body.get("cycle");
        assertEquals(4, cycle.size());
        assertEquals("A", cycle.get(0).asText());
        assertEquals("B", cycle.get(1).asText());
        assertEquals("C", cycle.get(2).asText());
        assertEquals("A", cycle.get(3).asText());
    }

    @Test
    void conflictReplayReturns409WithErrorCode() throws Exception {
        postTransform(dynamicReq("A", "B", 100L, "src"));
        Map<String, Object> changed = dynamicReq("A", "B", 100L, "src");
        changed.put("tx", 9.0);

        ResponseEntity<String> response = postTransform(changed);
        assertEquals(HttpStatus.CONFLICT, response.getStatusCode());
        JsonNode body = objectMapper.readTree(response.getBody());
        assertEquals("CONFLICTING_REPLAY", body.get("errorCode").asText());
    }

    @Test
    void frameNotFoundReturns404() throws Exception {
        postTransform(staticReq("A", "B", 0, 0, 0, "s1"));
        ResponseEntity<String> response = restTemplate.getForEntity(
                url("/api/transform?source=A&target=GHOST&stampNanos=0"), String.class);
        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
        JsonNode body = objectMapper.readTree(response.getBody());
        assertEquals("FRAME_NOT_FOUND", body.get("errorCode").asText());
    }

    @Test
    void disconnectedAndOutOfRangeReturnDistinct409Codes() throws Exception {
        postTransform(dynamicReq("A", "B", 1000L, "s1"));
        postTransform(dynamicReq("C", "D", 1000L, "s2"));

        ResponseEntity<String> disconnected = restTemplate.getForEntity(
                url("/api/transform?source=A&target=D&stampNanos=1000"), String.class);
        assertEquals(HttpStatus.CONFLICT, disconnected.getStatusCode());
        assertEquals("CHAIN_DISCONNECTED",
                objectMapper.readTree(disconnected.getBody()).get("errorCode").asText());

        ResponseEntity<String> before = restTemplate.getForEntity(
                url("/api/transform?source=A&target=B&stampNanos=500"), String.class);
        assertEquals(HttpStatus.CONFLICT, before.getStatusCode());
        assertEquals("TIME_BEFORE_EARLIEST",
                objectMapper.readTree(before.getBody()).get("errorCode").asText());

        ResponseEntity<String> after = restTemplate.getForEntity(
                url("/api/transform?source=A&target=B&stampNanos=2000"), String.class);
        assertEquals("TIME_AFTER_LATEST",
                objectMapper.readTree(after.getBody()).get("errorCode").asText());
    }

    @Test
    void invalidBodyReturns400() {
        Map<String, Object> bad = new LinkedHashMap<>();
        bad.put("parentFrame", "A");
        // childFrame 缺失
        bad.put("dynamic", false);
        bad.put("qw", 1.0);
        bad.put("source", "s1");

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<String> response = restTemplate.postForEntity(
                url("/api/transforms"), new HttpEntity<>(bad, headers), String.class);
        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
    }

    @Test
    void idempotentReplayReturnsCreatedOnce() {
        assertEquals(HttpStatus.CREATED, postTransform(staticReq("A", "B", 1, 0, 0, "s1")).getStatusCode());
        ResponseEntity<String> replay = postTransform(staticReq("A", "B", 1, 0, 0, "s1"));
        assertEquals(HttpStatus.CREATED, replay.getStatusCode());

        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM tf_transform_sample", Integer.class);
        assertEquals(1, count);
    }

    // ------------------------------------------------------------------

    private String url(String path) {
        return "http://localhost:" + port + path;
    }

    private ResponseEntity<String> postTransform(Map<String, Object> body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return restTemplate.postForEntity(
                url("/api/transforms"), new HttpEntity<>(body, headers), String.class);
    }

    private static Map<String, Object> staticReq(String parent, String child,
                                                 double tx, double ty, double tz, String source) {
        Map<String, Object> m = baseMap(parent, child, source);
        m.put("dynamic", false);
        m.put("stampNanos", null);
        m.put("tx", tx);
        m.put("ty", ty);
        m.put("tz", tz);
        return m;
    }

    private static Map<String, Object> dynamicReq(String parent, String child,
                                                  long stamp, String source) {
        Map<String, Object> m = baseMap(parent, child, source);
        m.put("dynamic", true);
        m.put("stampNanos", stamp);
        m.put("tx", 0.0);
        m.put("ty", 0.0);
        m.put("tz", 0.0);
        return m;
    }

    private static Map<String, Object> baseMap(String parent, String child, String source) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("parentFrame", parent);
        m.put("childFrame", child);
        m.put("qx", 0.0);
        m.put("qy", 0.0);
        m.put("qz", 0.0);
        m.put("qw", 1.0);
        m.put("source", source);
        return m;
    }
}
