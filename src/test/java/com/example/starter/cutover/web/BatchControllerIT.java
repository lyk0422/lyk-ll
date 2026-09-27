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
 * 批次 REST 入口端到端测试：发布冻结、同键重放、参数非法 400、业务拒绝 422、
 * 拓扑/阻断明细查询、执行门禁等待与前置失败传播。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class BatchControllerIT {

    private static final LocalDateTime BASE = LocalDateTime.of(2027, 1, 15, 2, 0);

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
        jdbc.update("DELETE FROM cutover_block");
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

    private Map<String, Object> cutoverBody(String id, String device) {
        return Map.of(
                "requestId", id,
                "title", "夜间割接-" + id,
                "deviceCode", device,
                "priority", "MEDIUM",
                "windowStart", BASE.toString(),
                "windowEnd", BASE.plusHours(2).toString(),
                "steps", List.of(
                        Map.of("name", "停流量", "type", "NORMAL"),
                        Map.of("name", "硬性检查", "type", "HARDCHECK")));
    }

    private long approveAndGetVersion(String id, String device, String uidPrefix) throws Exception {
        assertThat(post("/api/cutovers", cutoverBody(id, device)).getStatusCode())
                .isEqualTo(HttpStatus.CREATED);
        post("/api/cutovers/" + id + "/impact-analysis", null);
        assertThat(post("/api/cutovers/approve", Map.of(
                "requestUid", uidPrefix + "-ap", "requestId", id, "approved", true))
                .getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode view = mapper.readTree(
                rest.getForEntity("/api/cutovers/" + id, String.class).getBody());
        return view.path("version").asLong();
    }

    private Map<String, Object> member(String id, long version) {
        return Map.of("requestId", id, "expectedVersion", version);
    }

    @Test
    @DisplayName("HTTP 批次：发布201/同键重放200；空成员400；拓扑与门禁等待；前置失败后阻断明细")
    void batchEndToEnd() throws Exception {
        long va = approveAndGetVersion("HB-A", "DEV-CORE-SW-01", "a");
        long vb = approveAndGetVersion("HB-B", "DEV-CORE-SW-02", "b");

        Object publishBody = Map.of(
                "requestId", "HBATCH-1",
                "frozenAt", BASE.toString(),
                "members", List.of(member("HB-A", va), member("HB-B", vb)),
                "edges", List.of(Map.of("fromRequestId", "HB-A", "toRequestId", "HB-B")));

        ResponseEntity<String> created = post("/api/cutover-batches", publishBody);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        JsonNode createdNode = mapper.readTree(created.getBody());
        assertThat(createdNode.path("replayed").asBoolean()).isFalse();
        assertThat(createdNode.path("batch").path("status").asText()).isEqualTo("OPEN");
        assertThat(createdNode.path("batch").path("members")).hasSize(2);

        // 同键同参重放 200
        ResponseEntity<String> replay = post("/api/cutover-batches", publishBody);
        assertThat(replay.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(mapper.readTree(replay.getBody()).path("replayed").asBoolean()).isTrue();

        // 参数非法：members 为空 -> 400
        ResponseEntity<String> badRequest = post("/api/cutover-batches", Map.of(
                "requestId", "HBATCH-BAD",
                "frozenAt", BASE.toString(),
                "members", List.of(),
                "edges", List.of()));
        assertThat(badRequest.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        // 拓扑查询：节点按编号排序，B 的门禁未满足 waitingOn=[HB-A]
        ResponseEntity<String> topo = rest.getForEntity(
                "/api/cutover-batches/HBATCH-1/topology", String.class);
        assertThat(topo.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode topoNode = mapper.readTree(topo.getBody());
        assertThat(topoNode.path("nodes").get(0).path("requestId").asText()).isEqualTo("HB-A");
        JsonNode nodeB = topoNode.path("nodes").get(1);
        assertThat(nodeB.path("requestId").asText()).isEqualTo("HB-B");
        assertThat(nodeB.path("ready").asBoolean()).isFalse();
        assertThat(nodeB.path("waitingOn").get(0).asText()).isEqualTo("HB-A");

        // 前置未封板，B 启动 -> waiting=true（不占键）
        ResponseEntity<String> waiting = post("/api/cutovers/execute", Map.of(
                "requestUid", "hb-ex-b-wait", "requestId", "HB-B",
                "startTime", BASE.toString()));
        assertThat(waiting.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(mapper.readTree(waiting.getBody()).path("waiting").asBoolean()).isTrue();

        // A 启动后硬性检查失败 -> A 回退，同事务传播阻断 B
        assertThat(post("/api/cutovers/execute", Map.of(
                "requestUid", "hb-ex-a", "requestId", "HB-A", "startTime", BASE.toString()))
                .getStatusCode()).isEqualTo(HttpStatus.OK);
        ResponseEntity<String> failed = post("/api/cutovers/steps/report", Map.of(
                "requestUid", "hb-r-a1", "requestId", "HB-A", "seqNo", 1,
                "success", true, "reportedAt", BASE.plusMinutes(5).toString()));
        assertThat(failed.getStatusCode()).isEqualTo(HttpStatus.OK);
        ResponseEntity<String> rolledBack = post("/api/cutovers/steps/report", Map.of(
                "requestUid", "hb-r-a2", "requestId", "HB-A", "seqNo", 2,
                "success", false, "reportedAt", BASE.plusMinutes(10).toString()));
        JsonNode rbNode = mapper.readTree(rolledBack.getBody());
        assertThat(rbNode.path("request").path("status").asText()).isEqualTo("ROLLED_BACK");

        // B 最终 DEPENDENCY_BLOCKED，阻断明细可查询
        JsonNode bView = mapper.readTree(
                rest.getForEntity("/api/cutovers/HB-B", String.class).getBody());
        assertThat(bView.path("status").asText()).isEqualTo("DEPENDENCY_BLOCKED");

        ResponseEntity<String> blocks = rest.getForEntity(
                "/api/cutover-batches/HBATCH-1/blocks", String.class);
        assertThat(blocks.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(blocks.getBody()).contains("HB-B").contains("HB-A");

        // 被阻断后再次启动 B -> 422
        ResponseEntity<String> denied = post("/api/cutovers/execute", Map.of(
                "requestUid", "hb-ex-b-denied", "requestId", "HB-B",
                "startTime", BASE.toString()));
        assertThat(denied.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
    }

    @Test
    @DisplayName("HTTP 批次：环路业务拒绝 422，批次不存在 404")
    void cycle422AndNotFound404() throws Exception {
        long va = approveAndGetVersion("HC-A", "DEV-CORE-SW-01", "ca");
        long vb = approveAndGetVersion("HC-B", "DEV-CORE-SW-02", "cb");

        ResponseEntity<String> cycle = post("/api/cutover-batches", Map.of(
                "requestId", "HBATCH-CYC",
                "frozenAt", BASE.toString(),
                "members", List.of(member("HC-A", va), member("HC-B", vb)),
                "edges", List.of(
                        Map.of("fromRequestId", "HC-A", "toRequestId", "HC-B"),
                        Map.of("fromRequestId", "HC-B", "toRequestId", "HC-A"))));
        assertThat(cycle.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(cycle.getBody()).contains("环路");

        ResponseEntity<String> missing = rest.getForEntity(
                "/api/cutover-batches/NO-SUCH-BATCH", String.class);
        assertThat(missing.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }
}
