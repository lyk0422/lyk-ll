package com.example.starter.ros.service;

import com.example.starter.ros.RosIntegrationTestBase;
import com.example.starter.ros.bootstrap.DemoDataInitializer;
import com.example.starter.ros.dto.HotUpdateRequest;
import com.example.starter.ros.dto.HotUpdateResponse;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 跨节点事务式热更新主流程：双节点成功、版本冲突、节点拒绝、依赖冲突、
 * Schema 失败、重复事务幂等与同键异参 409。
 */
class HotUpdateIntegrationTest extends RosIntegrationTestBase {

    private static final String TX_PATH = "/api/ros/transactions/hot-update";

    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    @Autowired
    private HotUpdateService hotUpdateService;

    private HttpResponse<String> post(String path, Object body) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl() + path))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)))
                .build();
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private HotUpdateRequest twoNodeRequest(String txId, long expectedVersion) {
        HotUpdateRequest.NodeChangeSet camera = new HotUpdateRequest.NodeChangeSet(
                DemoDataInitializer.CAMERA_NODE, expectedVersion,
                Map.of("vision", Map.of("fps", 30)));
        HotUpdateRequest.NodeChangeSet planner = new HotUpdateRequest.NodeChangeSet(
                DemoDataInitializer.PLANNER_NODE, expectedVersion,
                Map.of("ctrl", Map.of("speed", 2.5)));
        return new HotUpdateRequest(txId, List.of(camera, planner), 1000L);
    }

    @Test
    void twoNodeSuccessCommitsVersionsValuesEventAndAppliesToNodes() throws Exception {
        HttpResponse<String> response = post(TX_PATH, twoNodeRequest("tx-success-1", 0L));

        assertThat(response.statusCode()).isEqualTo(HttpStatus.OK.value());
        HotUpdateResponse body = json.readValue(response.body(), HotUpdateResponse.class);
        assertThat(body.committed()).isTrue();
        assertThat(body.idempotentReplay()).isFalse();
        assertThat(body.nodeResults()).extracting(HotUpdateResponse.NodeResult::status)
                .containsExactly(HotUpdateResponse.NodeResult.ACCEPTED,
                        HotUpdateResponse.NodeResult.ACCEPTED);
        assertThat(body.nodeResults()).extracting(HotUpdateResponse.NodeResult::version)
                .containsExactly(1L, 1L);

        // 数据库：两节点版本单调 +1
        assertThat(jdbc.queryForObject(
                "SELECT version FROM ros_node_version WHERE node = ?",
                Long.class, DemoDataInitializer.CAMERA_NODE)).isEqualTo(1L);
        assertThat(jdbc.queryForObject(
                "SELECT version FROM ros_node_version WHERE node = ?",
                Long.class, DemoDataInitializer.PLANNER_NODE)).isEqualTo(1L);

        // 数据库：变更值已提交，读取合并默认值视图
        assertThat(jdbc.queryForObject(
                "SELECT param_value FROM ros_param_value WHERE node = ? AND group_name = ? AND name = ?",
                String.class, DemoDataInitializer.CAMERA_NODE, "vision", "fps")).isEqualTo("30");

        HttpResponse<String> cameraStatus = get("/api/ros/nodes/camera_node/status");
        JsonNode status = json.readTree(cameraStatus.body());
        assertThat(status.path("version").asLong()).isEqualTo(1L);
        // fps 已提交为 30，未变更的 enabled 仍取默认值 true
        assertThat(status.path("parameters").path("vision.fps").asInt()).isEqualTo(30);
        assertThat(status.path("parameters").path("vision.enabled").asBoolean()).isTrue();
        // 静态参数默认值在合并视图中可见，但不会因本次更新改变
        assertThat(status.path("parameters").path("vision.fps_max").asInt()).isEqualTo(60);

        // 一条事务事件
        Integer eventCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM ros_param_event WHERE transaction_id = ?",
                Integer.class, "tx-success-1");
        assertThat(eventCount).isEqualTo(1);
        Long cursor = jdbc.queryForObject(
                "SELECT cursor FROM ros_param_event WHERE transaction_id = ?",
                Long.class, "tx-success-1");
        assertThat(cursor).isEqualTo(1L);

        // 节点已应用值已更新
        assertThat(nodeAdapter.getApplied(DemoDataInitializer.CAMERA_NODE).get("vision.fps"))
                .isEqualTo(30L);
        assertThat(nodeAdapter.getApplied(DemoDataInitializer.PLANNER_NODE).get("ctrl.speed"))
                .isEqualTo(2.5);
    }

    private HttpResponse<String> get(String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl() + path))
                .timeout(Duration.ofSeconds(10))
                .GET().build();
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void versionConflictRejectsWholeTransactionWithoutAnyWrite() throws Exception {
        // expectedVersion=5 与当前版本 0 冲突
        HotUpdateResponse body = hotUpdateService.update(twoNodeRequest("tx-version-1", 5L));

        assertThat(body.committed()).isFalse();
        assertThat(body.nodeResults()).extracting(HotUpdateResponse.NodeResult::status)
                .containsOnly(HotUpdateResponse.NodeResult.VERSION_CONFLICT);

        assertNothingChanged();
        // 节点未被应用
        assertThat(nodeAdapter.getApplied(DemoDataInitializer.CAMERA_NODE)).isEmpty();
    }

    @Test
    void nodeRejectionRollsBackAcrossAllNodes() throws Exception {
        nodeAdapter.configure(DemoDataInitializer.PLANNER_NODE,
                new com.example.starter.ros.node.NodeBehavior(
                        com.example.starter.ros.node.NodeBehavior.Mode.REJECT,
                        "规划器拒绝该速度配置", 0L));

        HotUpdateResponse body = hotUpdateService.update(twoNodeRequest("tx-reject-1", 0L));

        assertThat(body.committed()).isFalse();
        assertThat(body.nodeResults()).anySatisfy(r -> {
            assertThat(r.node()).isEqualTo(DemoDataInitializer.CAMERA_NODE);
            assertThat(r.status()).isEqualTo(HotUpdateResponse.NodeResult.ACCEPTED);
        }).anySatisfy(r -> {
            assertThat(r.node()).isEqualTo(DemoDataInitializer.PLANNER_NODE);
            assertThat(r.status()).isEqualTo(HotUpdateResponse.NodeResult.REJECTED);
            assertThat(r.reason()).contains("规划器拒绝");
        });

        // 即使 camera 已接受，也不得提交任何值/版本/事件
        assertNothingChanged();
        assertThat(nodeAdapter.getApplied(DemoDataInitializer.CAMERA_NODE)).isEmpty();
        assertThat(nodeAdapter.getApplied(DemoDataInitializer.PLANNER_NODE)).isEmpty();
    }

    @Test
    void dependencyConflictIsRejectedBasedOnCandidateSnapshot() throws Exception {
        // speed=9.0 超过静态上限 max_speed=5.0：基于整笔候选快照的依赖校验失败
        HotUpdateRequest.NodeChangeSet planner = new HotUpdateRequest.NodeChangeSet(
                DemoDataInitializer.PLANNER_NODE, 0L,
                Map.of("ctrl", Map.of("speed", 9.0)));
        HotUpdateRequest request = new HotUpdateRequest("tx-dependency-1",
                List.of(planner), 1000L);

        HotUpdateResponse body = hotUpdateService.update(request);
        assertThat(body.committed()).isFalse();
        assertThat(body.nodeResults()).hasSize(1);
        assertThat(body.nodeResults().get(0).status())
                .isEqualTo(HotUpdateResponse.NodeResult.REJECTED);
        assertThat(body.nodeResults().get(0).reason()).contains("依赖校验未通过", "ctrl.speed");
        assertNothingChanged();
    }

    @Test
    void schemaTypeFailureIsRejectedAndNothingWritten() throws Exception {
        HotUpdateRequest.NodeChangeSet camera = new HotUpdateRequest.NodeChangeSet(
                DemoDataInitializer.CAMERA_NODE, 0L,
                Map.of("vision", Map.of("fps", "not-a-number")));
        HotUpdateRequest request = new HotUpdateRequest("tx-schema-1",
                List.of(camera), 1000L);

        HotUpdateResponse body = hotUpdateService.update(request);
        assertThat(body.committed()).isFalse();
        assertThat(body.nodeResults().get(0).status())
                .isEqualTo(HotUpdateResponse.NodeResult.REJECTED);
        assertThat(body.nodeResults().get(0).reason()).contains("候选配置构建失败");
        assertNothingChanged();
    }

    @Test
    void duplicateTransactionSameParamsReturnsOriginalResult() throws Exception {
        HotUpdateRequest first = twoNodeRequest("tx-idem-1", 0L);
        HotUpdateResponse firstBody = hotUpdateService.update(first);
        assertThat(firstBody.committed()).isTrue();

        // 同键同参重放：返回原结果且标记 idempotentReplay，不产生第二条事件、不二次推进版本
        HotUpdateResponse replay = hotUpdateService.update(
                twoNodeRequest("tx-idem-1", 0L));
        assertThat(replay.committed()).isTrue();
        assertThat(replay.idempotentReplay()).isTrue();
        assertThat(replay.nodeResults()).usingRecursiveFieldByFieldElementComparator()
                .isEqualTo(firstBody.nodeResults());

        Integer eventCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM ros_param_event WHERE transaction_id = ?",
                Integer.class, "tx-idem-1");
        assertThat(eventCount).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT version FROM ros_node_version WHERE node = ?",
                Long.class, DemoDataInitializer.CAMERA_NODE)).isEqualTo(1L);
    }

    @Test
    void duplicateTransactionDifferentParamsReturns409() throws Exception {
        hotUpdateService.update(twoNodeRequest("tx-conflict-1", 0L));

        // 同 transactionId 但变更值不同
        HotUpdateRequest.NodeChangeSet camera = new HotUpdateRequest.NodeChangeSet(
                DemoDataInitializer.CAMERA_NODE, 1L,
                Map.of("vision", Map.of("fps", 45)));
        HotUpdateRequest different = new HotUpdateRequest("tx-conflict-1",
                List.of(camera), 1000L);

        HttpResponse<String> response = post(TX_PATH, different);
        assertThat(response.statusCode()).isEqualTo(HttpStatus.CONFLICT.value());
        assertThat(response.body()).contains("tx-conflict-1", "不同参数");
    }

    private void assertNothingChanged() {
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ros_param_event", Integer.class))
                .isZero();
        assertThat(jdbc.queryForObject("SELECT version FROM ros_node_version WHERE node = ?",
                Long.class, DemoDataInitializer.CAMERA_NODE)).isZero();
        assertThat(jdbc.queryForObject("SELECT version FROM ros_node_version WHERE node = ?",
                Long.class, DemoDataInitializer.PLANNER_NODE)).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM ros_param_value WHERE node = ? AND name = ?",
                Integer.class, DemoDataInitializer.CAMERA_NODE, "fps")).isZero();
    }
}
