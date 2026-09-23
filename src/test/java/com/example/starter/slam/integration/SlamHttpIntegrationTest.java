package com.example.starter.slam.integration;

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
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * HTTP 全链路测试：JSON 编解码、路径/请求体一致性、200/202/404/409 状态码与错误体，
 * 以及"写入图 -> 提交两次优化（合并）-> 查询合并结果"的端到端主流程。
 */
@SpringBootTest(
        classes = com.example.starter.StarterApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.datasource.url=jdbc:h2:mem:slam_test_http;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
                "spring.datasource.driver-class-name=org.h2.Driver",
                "spring.datasource.username=sa",
                "spring.datasource.password=",
                "slam.queue.enabled=false"
        })
class SlamHttpIntegrationTest {

    private static final List<Double> INFO =
            List.of(100.0, 0.0, 0.0, 0.0, 100.0, 0.0, 0.0, 0.0, 100.0);

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private String base;

    @BeforeEach
    void setUp() {
        base = "http://localhost:" + port + "/api/slam";
        jdbcTemplate.update("DELETE FROM optimize_result");
        jdbcTemplate.update("DELETE FROM task_request");
        jdbcTemplate.update("DELETE FROM optimize_task");
        jdbcTemplate.update("DELETE FROM edge");
        jdbcTemplate.update("DELETE FROM key_frame");
        jdbcTemplate.update("DELETE FROM pose_graph");
    }

    private HttpHeaders jsonHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    private Map<String, Object> edgeBody(String id, String requestId, String from, String to,
                                         String type, Double score, Double residual) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", id);
        body.put("requestId", requestId);
        body.put("source", "loop".equals(type) ? "loop-detector" : "odom");
        body.put("version", 1);
        body.put("type", type);
        body.put("fromFrameId", from);
        body.put("toFrameId", to);
        body.put("measurement", List.of(1.0, 0.0, 0.0));
        body.put("information", INFO);
        body.put("score", score);
        body.put("residual", residual);
        return body;
    }

    @Test
    void endToEndWriteGraphSubmitTwiceAndQueryMergedResult() throws Exception {
        String graph = "g-http";
        // 写入 3 个关键帧 + 2 条里程计边
        for (int i = 0; i < 3; i++) {
            String id = "hf" + i;
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("id", id);
            body.put("source", "odom");
            body.put("version", 1);
            body.put("pose", List.of((double) i, 0.0, 0.0));
            ResponseEntity<String> resp = restTemplate.exchange(
                    base + "/graphs/{g}/frames/{f}", HttpMethod.PUT,
                    new HttpEntity<>(body, jsonHeaders()), String.class, graph, id);
            assertEquals(HttpStatus.OK, resp.getStatusCode());
        }
        for (int i = 0; i < 2; i++) {
            ResponseEntity<String> resp = restTemplate.exchange(
                    base + "/graphs/{g}/edges/{e}", HttpMethod.PUT,
                    new HttpEntity<>(edgeBody("he" + i, "hreq-e" + i, "hf" + i, "hf" + (i + 1),
                            "ODOMETRY", null, null), jsonHeaders()),
                    String.class, graph, "he" + i);
            assertEquals(HttpStatus.OK, resp.getStatusCode());
        }

        // 第一次提交优化
        Map<String, Object> submit1 = Map.of("requestId", "hreq-opt-1", "graphId", graph);
        ResponseEntity<String> r1 = restTemplate.postForEntity(
                base + "/optimizations", new HttpEntity<>(submit1, jsonHeaders()), String.class);
        assertEquals(HttpStatus.ACCEPTED, r1.getStatusCode());
        JsonNode task1 = objectMapper.readTree(r1.getBody());
        String taskId = task1.get("taskId").asText();
        assertTrue(task1.get("requestIds").toString().contains("hreq-opt-1"));

        // 第二次提交应被合并到同一任务
        Map<String, Object> submit2 = Map.of("requestId", "hreq-opt-2", "graphId", graph);
        ResponseEntity<String> r2 = restTemplate.postForEntity(
                base + "/optimizations", new HttpEntity<>(submit2, jsonHeaders()), String.class);
        assertEquals(HttpStatus.ACCEPTED, r2.getStatusCode());
        JsonNode task2 = objectMapper.readTree(r2.getBody());
        assertEquals(taskId, task2.get("taskId").asText());
        assertEquals(2, task2.get("requestIds").size());

        // 队列禁用，手动驱动由 MVC 测试不可达；通过任务查询验证合并状态与请求关系
        ResponseEntity<String> taskResp = restTemplate.getForEntity(
                base + "/tasks/" + taskId, String.class);
        assertEquals(HttpStatus.OK, taskResp.getStatusCode());
        JsonNode taskNode = objectMapper.readTree(taskResp.getBody());
        assertEquals("PENDING", taskNode.get("status").asText());

        ResponseEntity<String> reqResp = restTemplate.getForEntity(
                base + "/requests/hreq-opt-2", String.class);
        assertEquals(HttpStatus.OK, reqResp.getStatusCode());
        JsonNode reqNode = objectMapper.readTree(reqResp.getBody());
        assertEquals(taskId, reqNode.get("taskId").asText());
        assertEquals(taskId, reqNode.get("mergedIntoTaskId").asText());
    }

    @Test
    void conflictLoopClosureReturns409OnContentChangeAndRejectsBadLoopWithAudit() {
        String graph = "g-http-conflict";
        putFrame(graph, "cf0", 0.0);
        putFrame(graph, "cf1", 1.0);

        // 可信回环写入成功
        ResponseEntity<String> ok = restTemplate.exchange(
                base + "/graphs/{g}/edges/{e}", HttpMethod.PUT,
                new HttpEntity<>(edgeBody("cle", "creq-1", "cf0", "cf1", "LOOP", 0.95, 0.05),
                        jsonHeaders()),
                String.class, graph, "cle");
        assertEquals(HttpStatus.OK, ok.getStatusCode());

        // 同 requestId 改参 -> 409
        ResponseEntity<String> conflict = restTemplate.exchange(
                base + "/graphs/{g}/edges/{e}", HttpMethod.PUT,
                new HttpEntity<>(edgeBody("cle", "creq-1", "cf0", "cf1", "LOOP", 0.20, 0.05),
                        jsonHeaders()),
                String.class, graph, "cle");
        assertEquals(HttpStatus.CONFLICT, conflict.getStatusCode());
        assertTrue(conflict.getBody().contains("409") || conflict.getBody().contains("CONFLICT")
                || conflict.getBody().contains("already exists"));

        // 不可信回环（分数过低）以 200 返回 REJECTED 审计状态，不进图
        ResponseEntity<String> rejected = restTemplate.exchange(
                base + "/graphs/{g}/edges/{e}", HttpMethod.PUT,
                new HttpEntity<>(edgeBody("cle-bad", "creq-bad", "cf0", "cf1", "LOOP", 0.10, 0.05),
                        jsonHeaders()),
                String.class, graph, "cle-bad");
        assertEquals(HttpStatus.OK, rejected.getStatusCode());
        assertTrue(rejected.getBody().contains("REJECTED"));
    }

    @Test
    void edgeToMissingFrameReturns404AndUnknownTaskReturns404() {
        String graph = "g-http-404";
        putFrame(graph, "nf0", 0.0);
        putFrame(graph, "nf1", 1.0);

        ResponseEntity<String> missing = restTemplate.exchange(
                base + "/graphs/{g}/edges/{e}", HttpMethod.PUT,
                new HttpEntity<>(edgeBody("ne", "nreq", "nf0", "nGHOST", "ODOMETRY", null, null),
                        jsonHeaders()),
                String.class, graph, "ne");
        assertEquals(HttpStatus.NOT_FOUND, missing.getStatusCode());

        ResponseEntity<String> unknownTask = restTemplate.getForEntity(
                base + "/tasks/does-not-exist", String.class);
        assertEquals(HttpStatus.NOT_FOUND, unknownTask.getStatusCode());
    }

    @Test
    void pathBodyIdMismatchReturns400() {
        String graph = "g-http-400";
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", "other-frame");
        body.put("source", "odom");
        body.put("version", 1);
        body.put("pose", List.of(0.0, 0.0, 0.0));
        ResponseEntity<String> resp = restTemplate.exchange(
                base + "/graphs/{g}/frames/path-frame", HttpMethod.PUT,
                new HttpEntity<>(body, jsonHeaders()), String.class, graph);
        assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode());
    }

    private void putFrame(String graph, String id, double x) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", id);
        body.put("source", "odom");
        body.put("version", 1);
        body.put("pose", List.of(x, 0.0, 0.0));
        ResponseEntity<String> resp = restTemplate.exchange(
                base + "/graphs/{g}/frames/{f}", HttpMethod.PUT,
                new HttpEntity<>(body, jsonHeaders()), String.class, graph, id);
        assertEquals(HttpStatus.OK, resp.getStatusCode(), resp.getBody());
    }
}
