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
 * 批次 REST 入口端到端测试：发布/拓扑/阻断明细/结束批次与 400/404/409/422 语义。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class BatchControllerIT {

    private static final LocalDateTime BASE = LocalDateTime.of(2026, 12, 3, 2, 0);
    private static final LocalDateTime END = BASE.plusHours(2);

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

    private Map<String, Object> cutoverBody(String id, String device) {
        return Map.of(
                "requestId", id,
                "title", "夜间割接-" + id,
                "deviceCode", device,
                "priority", "MEDIUM",
                "windowStart", BASE.toString(),
                "windowEnd", END.toString(),
                "steps", List.of(Map.of("name", "停流量", "type", "NORMAL")));
    }

    /** 经 HTTP 完成 申请->分析->审批，返回当前版本号。 */
    private int prepareApproved(String id, String device) throws Exception {
        assertThat(post("/api/cutovers", cutoverBody(id, device)).getStatusCode())
                .isEqualTo(HttpStatus.CREATED);
        assertThat(post("/api/cutovers/" + id + "/impact-analysis", null).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(post("/api/cutovers/approve",
                Map.of("requestUid", "ap-" + id, "requestId", id, "approved", true))
                .getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode view = mapper.readTree(
                rest.getForEntity("/api/cutovers/" + id, String.class).getBody());
        return view.path("version").asInt();
    }

    private Map<String, Object> publishBody(String batchId, List<Map<String, Object>> members,
                                            List<Map<String, Object>> edges) {
        return Map.of(
                "requestId", batchId,
                "batchName", "批次-" + batchId,
                "publishTime", BASE.toString(),
                "members", members,
                "edges", edges);
    }

    private Map<String, Object> memberRef(String id, int version) {
        return Map.of("requestId", id, "expectedVersion", version);
    }

    @Test
    @DisplayName("HTTP 端到端：发布->门禁等待->前置封板->后继执行->结束批次；重放/异参/非法参数语义")
    void httpBatchEndToEnd() throws Exception {
        int va = prepareApproved("WBA", "DEV-DB-01");
        int vb = prepareApproved("WBB", "DEV-FW-01");

        // 发布批次 A -> B
        Map<String, Object> publish = publishBody("WBAT-1",
                List.of(memberRef("WBB", vb), memberRef("WBA", va)),
                List.of(Map.of("predecessor", "WBA", "successor", "WBB")));
        ResponseEntity<String> published = post("/api/batches", publish);
        assertThat(published.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        JsonNode batch = mapper.readTree(published.getBody()).path("batch");
        assertThat(batch.path("status").asText()).isEqualTo("PUBLISHED");
        assertThat(batch.path("version").asInt()).isEqualTo(1);
        // 拓扑按申请编号稳定升序
        assertThat(batch.path("members").get(0).path("requestId").asText()).isEqualTo("WBA");
        assertThat(batch.path("members").get(1).path("requestId").asText()).isEqualTo("WBB");

        // 同键同参重放 -> 200 replayed=true
        ResponseEntity<String> replay = post("/api/batches", publish);
        assertThat(replay.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(mapper.readTree(replay.getBody()).path("replayed").asBoolean()).isTrue();

        // 同键异参 -> 409
        Map<String, Object> mutated = publishBody("WBAT-1",
                List.of(memberRef("WBA", va)), List.of());
        assertThat(post("/api/batches", mutated).getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT);

        // 空成员集合 -> 400
        assertThat(post("/api/batches", publishBody("WBAT-X", List.of(), List.of()))
                .getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        // 后继启动：前置未封板 -> 等待，不写步骤
        ResponseEntity<String> wait = post("/api/cutovers/execute",
                Map.of("requestUid", "ex-WBB", "requestId", "WBB",
                        "startTime", BASE.toString()));
        assertThat(wait.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode waitNode = mapper.readTree(wait.getBody());
        assertThat(waitNode.path("message").asText()).contains("等待");
        assertThat(waitNode.path("request").path("status").asText()).isEqualTo("APPROVED");

        // 前置执行、验证、封板
        assertThat(post("/api/cutovers/execute",
                Map.of("requestUid", "ex-WBA", "requestId", "WBA",
                        "startTime", BASE.toString())).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(post("/api/cutovers/steps/report",
                Map.of("requestUid", "rp-WBA-1", "requestId", "WBA", "seqNo", 1,
                        "success", true, "reportedAt", BASE.plusMinutes(5).toString()))
                .getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(post("/api/cutovers/seal",
                Map.of("requestUid", "seal-WBA", "requestId", "WBA")).getStatusCode())
                .isEqualTo(HttpStatus.OK);

        // 前置封板后后继放行
        ResponseEntity<String> go = post("/api/cutovers/execute",
                Map.of("requestUid", "ex-WBB", "requestId", "WBB",
                        "startTime", BASE.toString()));
        assertThat(mapper.readTree(go.getBody()).path("request").path("status").asText())
                .isEqualTo("EXECUTING");

        // 阻断明细为空；拓扑查询入口
        ResponseEntity<String> blocks = rest.getForEntity("/api/batches/WBAT-1/blocks",
                String.class);
        assertThat(blocks.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(mapper.readTree(blocks.getBody()).size()).isZero();
        assertThat(rest.getForEntity("/api/batches/WBAT-NONE", String.class).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);

        // 后继完成并封板后结束批次：版本 1 -> 2
        assertThat(post("/api/cutovers/steps/report",
                Map.of("requestUid", "rp-WBB-1", "requestId", "WBB", "seqNo", 1,
                        "success", true, "reportedAt", BASE.plusMinutes(10).toString()))
                .getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(post("/api/cutovers/seal",
                Map.of("requestUid", "seal-WBB", "requestId", "WBB")).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        ResponseEntity<String> closed = post("/api/batches/close",
                Map.of("requestUid", "cl-WBAT-1", "batchId", "WBAT-1",
                        "expectedVersion", 1, "closeTime", END.plusHours(1).toString()));
        assertThat(closed.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode closedNode = mapper.readTree(closed.getBody()).path("batch");
        assertThat(closedNode.path("status").asText()).isEqualTo("CLOSED");
        assertThat(closedNode.path("version").asInt()).isEqualTo(2);

        // 版本不符 -> 422
        assertThat(post("/api/batches/close",
                Map.of("requestUid", "cl-WBAT-1-b", "batchId", "WBAT-1",
                        "expectedVersion", 1, "closeTime", END.plusHours(2).toString()))
                .getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
    }

    @Test
    @DisplayName("HTTP：前置回退后阻断明细入口返回首个阻断来源集合")
    void httpBlockDetailsAfterRollback() throws Exception {
        int va = prepareApproved("WBC", "DEV-DB-01");
        int vb = prepareApproved("WBD", "DEV-FW-01");
        assertThat(post("/api/batches", publishBody("WBAT-2",
                List.of(memberRef("WBC", va), memberRef("WBD", vb)),
                List.of(Map.of("predecessor", "WBC", "successor", "WBD"))))
                .getStatusCode()).isEqualTo(HttpStatus.CREATED);

        // 前置执行并失败 -> 回退 + 同事务阻断传播
        assertThat(post("/api/cutovers/execute",
                Map.of("requestUid", "ex-WBC", "requestId", "WBC",
                        "startTime", BASE.toString())).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(post("/api/cutovers/steps/report",
                Map.of("requestUid", "rp-WBC-1", "requestId", "WBC", "seqNo", 1,
                        "success", false, "reportedAt", BASE.plusMinutes(5).toString()))
                .getStatusCode()).isEqualTo(HttpStatus.OK);

        // 阻断明细：WBD 被阻断，来源 WBC
        ResponseEntity<String> blocks = rest.getForEntity("/api/batches/WBAT-2/blocks",
                String.class);
        JsonNode blockList = mapper.readTree(blocks.getBody());
        assertThat(blockList.size()).isEqualTo(1);
        assertThat(blockList.get(0).path("requestId").asText()).isEqualTo("WBD");
        assertThat(blockList.get(0).path("blockSources").get(0).asText()).isEqualTo("WBC");

        // 被阻断后继启动 -> 422
        assertThat(post("/api/cutovers/execute",
                Map.of("requestUid", "ex-WBD", "requestId", "WBD",
                        "startTime", BASE.toString())).getStatusCode())
                .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
    }
}
