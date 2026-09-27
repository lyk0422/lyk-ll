package com.example.starter.cutover.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 依赖批次真实 HTTP 入口测试：发布/重放/异参 409、参数非法 400、
 * 对象不存在 404、业务拒绝 422、门禁等待与阻断查询。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class CutoverBatchControllerIT {

    private static final LocalDateTime BASE = LocalDateTime.of(2026, 12, 8, 2, 0);
    private static final LocalDateTime PUBLISHED_AT = LocalDateTime.of(2026, 12, 7, 20, 0);

    @Autowired
    private TestRestTemplate rest;
    @Autowired
    private ObjectMapper mapper;
    @Autowired
    private JdbcTemplate jdbc;

    private final HttpHeaders json = new HttpHeaders();

    @BeforeEach
    void setUp() {
        json.setContentType(MediaType.APPLICATION_JSON);
        jdbc.update("DELETE FROM cutover_batch_edge");
        jdbc.update("DELETE FROM cutover_batch_member");
        jdbc.update("DELETE FROM cutover_batch");
        jdbc.update("DELETE FROM cutover_event");
        jdbc.update("DELETE FROM cutover_step");
        jdbc.update("DELETE FROM cutover_request");
    }

    private ResponseEntity<String> post(String path, Object body) {
        return rest.postForEntity(path, new HttpEntity<>(body, json), String.class);
    }

    private Map<String, Object> cutoverBody(String id, String device,
                                            LocalDateTime s, LocalDateTime e) {
        return Map.of(
                "requestId", id,
                "title", "夜间割接-" + id,
                "deviceCode", device,
                "priority", "MEDIUM",
                "windowStart", s.toString(),
                "windowEnd", e.toString(),
                "steps", List.of(
                        Map.of("name", "停流量", "type", "NORMAL"),
                        Map.of("name", "硬检查", "type", "HARDCHECK")));
    }

    private Map<String, Object> member(String id, int version) {
        return Map.of("requestId", id, "expectedVersion", version);
    }

    private void prepareApproved(String id, String device) {
        post("/api/cutovers", cutoverBody(id, device, BASE, BASE.plusHours(2)));
        post("/api/cutovers/" + id + "/impact-analysis", null);
        assertThat(post("/api/cutovers/approve", Map.of(
                "requestUid", "ap-" + id, "requestId", id, "approved", true))
                .getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("批次 HTTP：发布 201、同键同参重放 200、异参 409、非法参数 400、不存在 404")
    void batchPublishReplayConflictAndErrors() throws Exception {
        prepareApproved("H-A", "DEV-CORE-SW-01");
        prepareApproved("H-B", "DEV-CORE-SW-02");
        LocalDateTime end = BASE.plusHours(2);

        Object publishBody = Map.of(
                "requestId", "HB-1",
                "publishedAt", PUBLISHED_AT.toString(),
                "members", List.of(member("H-A", 3), member("H-B", 3)),
                "edges", List.of(Map.of("fromRequestId", "H-A", "toRequestId", "H-B")));

        ResponseEntity<String> created = post("/api/cutover-batches", publishBody);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        JsonNode node = mapper.readTree(created.getBody());
        assertThat(node.path("replayed").asBoolean()).isFalse();
        assertThat(node.path("batch").path("members")).hasSize(2);
        // 绑定后版本 3 -> 4
        assertThat(node.path("batch").path("members").get(0).path("currentVersion").asInt())
                .isEqualTo(4);

        // 同键同参重放 -> 200
        ResponseEntity<String> replay = post("/api/cutover-batches", publishBody);
        assertThat(replay.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(mapper.readTree(replay.getBody()).path("replayed").asBoolean()).isTrue();

        // 同键异参（去掉依赖边）-> 409
        Object conflictBody = Map.of(
                "requestId", "HB-1",
                "publishedAt", PUBLISHED_AT.toString(),
                "members", List.of(member("H-A", 3), member("H-B", 3)),
                "edges", List.of());
        ResponseEntity<String> conflict = post("/api/cutover-batches", conflictBody);
        assertThat(conflict.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);

        // 参数非法：members 为空 -> 400
        ResponseEntity<String> badRequest = post("/api/cutover-batches", Map.of(
                "requestId", "HB-BAD",
                "publishedAt", PUBLISHED_AT.toString(),
                "members", List.of(),
                "edges", List.of()));
        assertThat(badRequest.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        // 查询不存在批次 -> 404
        ResponseEntity<String> notFound = rest.getForEntity(
                "/api/cutover-batches/HB-NOPE", String.class);
        assertThat(notFound.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        ResponseEntity<String> blocksNotFound = rest.getForEntity(
                "/api/cutover-batches/HB-NOPE/blocks", String.class);
        assertThat(blocksNotFound.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

        // 拓扑查询按申请编号排序
        ResponseEntity<String> view = rest.getForEntity(
                "/api/cutover-batches/HB-1", String.class);
        JsonNode viewNode = mapper.readTree(view.getBody());
        assertThat(viewNode.path("members").get(0).path("requestId").asText()).isEqualTo("H-A");
        assertThat(viewNode.path("edges").get(0).path("fromRequestId").asText())
                .isEqualTo("H-A");

        // 门禁：H-B 直接启动 -> 等待（200, waiting=true），前置仍 APPROVED
        ResponseEntity<String> waiting = post("/api/cutovers/execute", Map.of(
                "requestUid", "ex-H-B", "requestId", "H-B", "startTime", BASE.toString()));
        assertThat(waiting.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(mapper.readTree(waiting.getBody()).path("waiting").asBoolean()).isTrue();

        // 前置回退：H-A 硬检查失败 -> ROLLED_BACK；查询阻断明细含 H-B
        post("/api/cutovers/execute", Map.of(
                "requestUid", "ex-H-A", "requestId", "H-A", "startTime", BASE.toString()));
        post("/api/cutovers/steps/report", Map.of(
                "requestUid", "r-H-A1", "requestId", "H-A", "seqNo", 1,
                "success", true, "reportedAt", BASE.plusMinutes(10).toString()));
        post("/api/cutovers/steps/report", Map.of(
                "requestUid", "r-H-A2", "requestId", "H-A", "seqNo", 2,
                "success", false, "reportedAt", BASE.plusMinutes(20).toString()));
        ResponseEntity<String> blocks = rest.getForEntity(
                "/api/cutover-batches/HB-1/blocks", String.class);
        assertThat(blocks.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode blocksNode = mapper.readTree(blocks.getBody());
        assertThat(blocksNode).hasSize(1);
        assertThat(blocksNode.get(0).path("requestId").asText()).isEqualTo("H-B");
        assertThat(blocksNode.get(0).path("blockedBy").asText()).isEqualTo("H-A");

        // 阻断后启动后继 -> 422
        ResponseEntity<String> denied = post("/api/cutovers/execute", Map.of(
                "requestUid", "ex-H-B2", "requestId", "H-B",
                "startTime", BASE.plusMinutes(30).toString()));
        assertThat(denied.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);

        // 版本不符整批拒绝 -> 422（新批次，成员版本期望错误）
        prepareApproved("H-C", "DEV-DB-01");
        ResponseEntity<String> versionReject = post("/api/cutover-batches", Map.of(
                "requestId", "HB-2",
                "publishedAt", PUBLISHED_AT.toString(),
                "members", List.of(member("H-C", 99)),
                "edges", List.of()));
        assertThat(versionReject.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(versionReject.getBody()).contains("版本");

        // 失败不占键：修正版本后同 requestId 发布成功 201
        ResponseEntity<String> retry = post("/api/cutover-batches", Map.of(
                "requestId", "HB-2",
                "publishedAt", PUBLISHED_AT.toString(),
                "members", List.of(member("H-C", 3)),
                "edges", List.of()));
        assertThat(retry.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }
}
