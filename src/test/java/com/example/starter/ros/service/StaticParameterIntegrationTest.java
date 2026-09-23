package com.example.starter.ros.service;

import com.example.starter.ros.RosIntegrationTestBase;
import com.example.starter.ros.bootstrap.DemoDataInitializer;
import com.example.starter.ros.dto.HotUpdateRequest;
import com.example.starter.ros.dto.HotUpdateResponse;
import com.example.starter.ros.dto.PendingRestartRequest;
import com.example.starter.ros.error.ApiConflictException;
import com.example.starter.ros.node.NodeBehavior;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 静态参数保护：热更新改静态参数返回 409 并指出参数名；
 * 静态参数只能创建待重启配置，不出现在已提交值或节点已应用值中。
 */
class StaticParameterIntegrationTest extends RosIntegrationTestBase {

    @Autowired
    private HotUpdateService hotUpdateService;

    @Autowired
    private PendingRestartService pendingRestartService;

    @Test
    void hotUpdateOfStaticParameterIs409NamingTheParameter() {
        HotUpdateRequest.NodeChangeSet camera = new HotUpdateRequest.NodeChangeSet(
                DemoDataInitializer.CAMERA_NODE, 0L,
                // fps_max 是静态参数
                Map.of("vision", Map.of("fps_max", 120)));
        HotUpdateRequest request = new HotUpdateRequest("tx-static-1",
                List.of(camera), 1000L);

        assertThatThrownBy(() -> hotUpdateService.update(request))
                .isInstanceOf(ApiConflictException.class)
                .hasMessageContaining("静态参数不得通过热更新改变")
                .hasMessageContaining("vision.fps_max");

        // 未提交任何值/版本/事件
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ros_param_event", Integer.class)).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT version FROM ros_node_version WHERE node = ?",
                Long.class, DemoDataInitializer.CAMERA_NODE)).isZero();
        Integer fpsMaxRows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM ros_param_value WHERE node = ? AND name = ?",
                Integer.class, DemoDataInitializer.CAMERA_NODE, "fps_max");
        assertThat(fpsMaxRows).isZero();
        assertThat(nodeAdapter.getApplied(DemoDataInitializer.CAMERA_NODE)).doesNotContainKey("vision.fps_max");
    }

    @Test
    void staticParameterCanOnlyBePendingRestartAndIsNotApplied() {
        PendingRestartRequest request = new PendingRestartRequest(
                "tx-pending-1",
                DemoDataInitializer.CAMERA_NODE,
                Map.of("vision", Map.of("fps_max", 120)));

        List<String> registered = pendingRestartService.register(request);
        assertThat(registered).containsExactly("vision.fps_max");

        // 待重启项可查
        Map<String, String> pending = jdbc.query(
                "SELECT name, param_value FROM ros_pending_restart WHERE node = ?",
                rs -> {
                    Map<String, String> map = new java.util.LinkedHashMap<>();
                    while (rs.next()) {
                        map.put(rs.getString("name"), rs.getString("param_value"));
                    }
                    return map;
                }, DemoDataInitializer.CAMERA_NODE);
        assertThat(pending).containsEntry("fps_max", "120");

        // 不进已提交值、不推进版本、不产生事件、不应用到节点
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ros_param_event", Integer.class)).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT version FROM ros_node_version WHERE node = ?",
                Long.class, DemoDataInitializer.CAMERA_NODE)).isZero();
        assertThat(nodeAdapter.getApplied(DemoDataInitializer.CAMERA_NODE)).doesNotContainKey("vision.fps_max");
    }

    @Test
    void dynamicParameterViaPendingRestartIsRejected() {
        PendingRestartRequest request = new PendingRestartRequest(
                "tx-pending-bad", DemoDataInitializer.CAMERA_NODE,
                Map.of("vision", Map.of("fps", 30)));
        assertThatThrownBy(() -> pendingRestartService.register(request))
                .isInstanceOf(ApiConflictException.class)
                .hasMessageContaining("动态参数不应走待重启通道");
    }

    @Test
    void staticAndDynamicMixedInOneHotUpdateIs409WithoutPartialApply() {
        HotUpdateRequest.NodeChangeSet camera = new HotUpdateRequest.NodeChangeSet(
                DemoDataInitializer.CAMERA_NODE, 0L,
                Map.of("vision", Map.of("fps", 30, "fps_max", 120)));
        HotUpdateRequest request = new HotUpdateRequest("tx-static-mix",
                List.of(camera), 1000L);

        assertThatThrownBy(() -> hotUpdateService.update(request))
                .isInstanceOf(ApiConflictException.class)
                .hasMessageContaining("vision.fps_max");

        // 同笔中的动态参数也不得部分写入
        Integer fpsRows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM ros_param_value WHERE node = ? AND name = ?",
                Integer.class, DemoDataInitializer.CAMERA_NODE, "fps");
        assertThat(fpsRows).isZero();
        assertThat(nodeAdapter.getApplied(DemoDataInitializer.CAMERA_NODE)).isEmpty();
    }

    @Test
    void timeoutNodeRollsBackEntireTransactionAcrossNodes() {
        // planner 在时限内无响应（默认测试时限 500ms）
        nodeAdapter.configure(DemoDataInitializer.PLANNER_NODE,
                new NodeBehavior(NodeBehavior.Mode.TIMEOUT, null, 0L));

        HotUpdateRequest.NodeChangeSet camera = new HotUpdateRequest.NodeChangeSet(
                DemoDataInitializer.CAMERA_NODE, 0L,
                Map.of("vision", Map.of("fps", 30)));
        HotUpdateRequest.NodeChangeSet planner = new HotUpdateRequest.NodeChangeSet(
                DemoDataInitializer.PLANNER_NODE, 0L,
                Map.of("ctrl", Map.of("speed", 2.5)));
        HotUpdateRequest request = new HotUpdateRequest("tx-timeout-1",
                List.of(camera, planner), 300L);

        long started = System.nanoTime();
        HotUpdateResponse body = hotUpdateService.update(request);
        long elapsedMs = (System.nanoTime() - started) / 1_000_000;

        assertThat(body.committed()).isFalse();
        assertThat(body.nodeResults()).anySatisfy(r -> {
            assertThat(r.node()).isEqualTo(DemoDataInitializer.PLANNER_NODE);
            assertThat(r.status()).isEqualTo(HotUpdateResponse.NodeResult.TIMEOUT);
            assertThat(r.reason()).contains("300ms");
        });
        // 不应长时间挂死：future.get 超时即返回
        assertThat(elapsedMs).isLessThan(3000L);

        // 数据库值/版本/事件保持原状
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ros_param_event", Integer.class)).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT version FROM ros_node_version WHERE node = ?",
                Long.class, DemoDataInitializer.CAMERA_NODE)).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT version FROM ros_node_version WHERE node = ?",
                Long.class, DemoDataInitializer.PLANNER_NODE)).isZero();
        assertThat(nodeAdapter.getApplied(DemoDataInitializer.CAMERA_NODE)).isEmpty();
        assertThat(nodeAdapter.getApplied(DemoDataInitializer.PLANNER_NODE)).isEmpty();
    }
}
