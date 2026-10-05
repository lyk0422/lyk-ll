package com.example.starter.cutover.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.starter.cutover.Priority;
import com.example.starter.cutover.dto.ApproveRequest;
import com.example.starter.cutover.dto.CreateCutoverRequest;
import com.example.starter.cutover.dto.StepDefinition;
import com.example.starter.cutover.StepType;
import com.example.starter.cutover.service.CutoverService;
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
 * 批次 REST 入口端到端测试：发布/拓扑/阻断明细，400/404/409/422 错误语义。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class BatchControllerIT {

    private static final LocalDateTime BASE = LocalDateTime.of(2026, 12, 10, 2, 0);
    private static final LocalDateTime PUBLISH_AT = LocalDateTime.of(2026, 12, 9, 23, 0);

    @Autowired
    private TestRestTemplate rest;
    @Autowired
    private ObjectMapper mapper;
    @Autowired
    private CutoverService cutoverService;
    @Autowired
    private JdbcTemplate jdbc;

    private final HttpHeaders json = new HttpHeaders();

    @BeforeEach
    void setUp() {
        json.setContentType(MediaType.APPLICATION_JSON);
        jdbc.update("DELETE FROM cutover_block_record");
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

    /** 通过服务层准备两个已批准申请（版本号 = 3）。 */
    private void prepareApproved(String id, String device) {
        cutoverService.create(new CreateCutoverRequest(id, "割接-" + id, device,
                Priority.MEDIUM, BASE, BASE.plusHours(2),
                List.of(new StepDefinition("硬性检查", StepType.HARDCHECK))));
        cutoverService.analyze(id);
        cutoverService.approve(new ApproveRequest("ap-" + id, id, true, null));
    }

    private Map<String, Object> publishBody(String batchId) {
        return Map.of(
                "batchId", batchId,
                "publishedAt", PUBLISH_AT.toString(),
                "members", List.of(
                        Map.of("requestId", "W-A", "expectedVersion", 3),
                        Map.of("requestId", "W-B", "expectedVersion", 3)),
                "edges", List.of(Map.of("predecessor", "W-A", "successor", "W-B")));
    }

    @Test
    @DisplayName("批次 HTTP 主流程：发布 201 -> 同键重放 200 -> 拓扑/阻断明细查询 -> 门禁等待 -> 封板后启动")
    void batchHttpFlow() throws Exception {
        prepareApproved("W-A", "DEV-CORE-SW-01");
        prepareApproved("W-B", "DEV-CORE-SW-02");

        ResponseEntity<String> published = post("/api/cutover-batches", publishBody("B-HTTP"));
        assertThat(published.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        JsonNode batch = mapper.readTree(published.getBody()).path("batch");
        assertThat(batch.path("status").asText()).isEqualTo("PUBLISHED");
        assertThat(batch.path("version").asInt()).isEqualTo(1);
        assertThat(batch.path("members").get(0).path("requestId").asText()).isEqualTo("W-A");
        assertThat(batch.path("members").get(0).path("requestVersion").asInt()).isEqualTo(3);

        // 同键同参重放：200 + replayed=true
        ResponseEntity<String> replay = post("/api/cutover-batches", publishBody("B-HTTP"));
        assertThat(replay.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(mapper.readTree(replay.getBody()).path("replayed").asBoolean()).isTrue();

        // 同键异参：409
        ResponseEntity<String> conflict = post("/api/cutover-batches", Map.of(
                "batchId", "B-HTTP",
                "publishedAt", PUBLISH_AT.plusMinutes(1).toString(),
                "members", List.of(
                        Map.of("requestId", "W-A", "expectedVersion", 3),
                        Map.of("requestId", "W-B", "expectedVersion", 3)),
                "edges", List.of(Map.of("predecessor", "W-A", "successor", "W-B"))));
        assertThat(conflict.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);

        // 拓扑查询：按申请编号稳定排序
        ResponseEntity<String> topo = rest.getForEntity("/api/cutover-batches/B-HTTP", String.class);
        assertThat(topo.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode topoNode = mapper.readTree(topo.getBody());
        assertThat(topoNode.path("members")).hasSize(2);
        assertThat(topoNode.path("edges").get(0).path("predecessor").asText()).isEqualTo("W-A");

        // 阻断明细为空
        ResponseEntity<String> blocks = rest.getForEntity(
                "/api/cutover-batches/B-HTTP/blocks", String.class);
        assertThat(blocks.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(mapper.readTree(blocks.getBody())).hasSize(0);

        // 门禁：前置未封板，W-B 启动返回等待（200，状态不变）
        ResponseEntity<String> waiting = post("/api/cutovers/execute", Map.of(
                "requestUid", "w-ex-b", "requestId", "W-B", "startTime", BASE.toString()));
        assertThat(waiting.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode waitingNode = mapper.readTree(waiting.getBody());
        assertThat(waitingNode.path("message").asText()).contains("等待");
        assertThat(waitingNode.path("request").path("status").asText()).isEqualTo("APPROVED");

        // 前置完成并封板后，W-B 可启动
        assertThat(post("/api/cutovers/execute", Map.of(
                "requestUid", "w-ex-a", "requestId", "W-A", "startTime", BASE.toString()))
                .getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(post("/api/cutovers/steps/report", Map.of(
                "requestUid", "w-r1-a", "requestId", "W-A", "seqNo", 1,
                "success", true, "reportedAt", BASE.plusMinutes(10).toString()))
                .getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(post("/api/cutovers/seal", Map.of(
                "requestUid", "w-seal-a", "requestId", "W-A"))
                .getStatusCode()).isEqualTo(HttpStatus.OK);
        ResponseEntity<String> started = post("/api/cutovers/execute", Map.of(
                "requestUid", "w-ex-b2", "requestId", "W-B", "startTime", BASE.toString()));
        assertThat(started.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(mapper.readTree(started.getBody()).path("request").path("status").asText())
                .isEqualTo("EXECUTING");
    }

    @Test
    @DisplayName("批次 HTTP 错误语义：参数非法 400、批次不存在 404、环路等业务拒绝 422")
    void batchHttpErrorSemantics() {
        prepareApproved("W-A", "DEV-CORE-SW-01");
        prepareApproved("W-B", "DEV-CORE-SW-02");

        // 400：缺少 publishedAt
        ResponseEntity<String> bad = post("/api/cutover-batches", Map.of(
                "batchId", "B-BAD",
                "members", List.of(Map.of("requestId", "W-A", "expectedVersion", 3))));
        assertThat(bad.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        // 400：expectedVersion 缺省
        ResponseEntity<String> badVersion = post("/api/cutover-batches", Map.of(
                "batchId", "B-BAD2",
                "publishedAt", PUBLISH_AT.toString(),
                "members", List.of(Map.of("requestId", "W-A"))));
        assertThat(badVersion.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        // 404：批次不存在
        ResponseEntity<String> notFound = rest.getForEntity(
                "/api/cutover-batches/B-NONE", String.class);
        assertThat(notFound.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        ResponseEntity<String> blocksNotFound = rest.getForEntity(
                "/api/cutover-batches/B-NONE/blocks", String.class);
        assertThat(blocksNotFound.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

        // 422：环路整批拒绝
        ResponseEntity<String> cycle = post("/api/cutover-batches", Map.of(
                "batchId", "B-CYCLE",
                "publishedAt", PUBLISH_AT.toString(),
                "members", List.of(
                        Map.of("requestId", "W-A", "expectedVersion", 3),
                        Map.of("requestId", "W-B", "expectedVersion", 3)),
                "edges", List.of(
                        Map.of("predecessor", "W-A", "successor", "W-B"),
                        Map.of("predecessor", "W-B", "successor", "W-A"))));
        assertThat(cycle.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);

        // 422：版本不符
        ResponseEntity<String> versionMismatch = post("/api/cutover-batches", Map.of(
                "batchId", "B-VER",
                "publishedAt", PUBLISH_AT.toString(),
                "members", List.of(Map.of("requestId", "W-A", "expectedVersion", 1))));
        assertThat(versionMismatch.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);

        // 404：成员申请不存在
        ResponseEntity<String> memberMissing = post("/api/cutover-batches", Map.of(
                "batchId", "B-MISSING",
                "publishedAt", PUBLISH_AT.toString(),
                "members", List.of(Map.of("requestId", "W-NONE", "expectedVersion", 1))));
        assertThat(memberMissing.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("批次 HTTP 失败传播：前置回退后阻断明细可查，后继启动 422")
    void batchHttpPropagation() throws Exception {
        prepareApproved("W-A", "DEV-CORE-SW-01");
        prepareApproved("W-B", "DEV-CORE-SW-02");
        assertThat(post("/api/cutover-batches", publishBody("B-PROP"))
                .getStatusCode()).isEqualTo(HttpStatus.CREATED);

        // 前置执行并失败 -> 回退 + 同事务传播
        post("/api/cutovers/execute", Map.of(
                "requestUid", "p-ex-a", "requestId", "W-A", "startTime", BASE.toString()));
        post("/api/cutovers/steps/report", Map.of(
                "requestUid", "p-r1-a", "requestId", "W-A", "seqNo", 1,
                "success", false, "reportedAt", BASE.plusMinutes(10).toString()));

        // 阻断明细
        ResponseEntity<String> blocks = rest.getForEntity(
                "/api/cutover-batches/B-PROP/blocks", String.class);
        JsonNode blockNode = mapper.readTree(blocks.getBody());
        assertThat(blockNode).hasSize(1);
        assertThat(blockNode.get(0).path("requestId").asText()).isEqualTo("W-B");
        assertThat(blockNode.get(0).path("sourceRequestId").asText()).isEqualTo("W-A");
        assertThat(blockNode.get(0).path("cause").asText()).isEqualTo("ROLLBACK");

        // 后继启动 -> 422（已依赖阻断）
        ResponseEntity<String> denied = post("/api/cutovers/execute", Map.of(
                "requestUid", "p-ex-b", "requestId", "W-B", "startTime", BASE.toString()));
        assertThat(denied.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);

        // 拓扑中保留首个阻断来源集合
        ResponseEntity<String> topo = rest.getForEntity(
                "/api/cutover-batches/B-PROP", String.class);
        JsonNode members = mapper.readTree(topo.getBody()).path("members");
        assertThat(members.get(1).path("requestStatus").asText())
                .isEqualTo("DEPENDENCY_BLOCKED");
        assertThat(members.get(1).path("blockingSources").get(0).asText()).isEqualTo("W-A");
    }
}
