package com.example.starter.slam;

import com.example.starter.slam.service.GraphWriteService;
import com.example.starter.slam.web.dto.EdgeRequest;
import com.example.starter.slam.web.dto.KeyframeRequest;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 回环版本化阈值：version=7 使用覆盖阈值（scoreMin 降至 0.50、residualMax 放宽到 2.0），
 * 未命中覆盖的版本仍使用默认阈值。
 */
@TestPropertySource(properties = {
        "slam.loop.version-overrides.7.score-min=0.50",
        "slam.loop.version-overrides.7.residual-max=2.0",
        "slam.loop.version-overrides.7.covariance-trace-max=5.0"
})
class VersionedLoopThresholdTest extends AbstractSlamIntegrationTest {

    @Autowired
    private GraphWriteService graphWriteService;

    private KeyframeRequest keyframe(String id, String requestId) {
        return new KeyframeRequest(id, "lidar-a", 0, 1.0, 2.0, 0.1,
                JsonNodeFactory.instance.objectNode().put("scanId", id), requestId);
    }

    private EdgeRequest loop(String id, int version, double score, double residual,
                             java.util.List<Double> covariance, String requestId) {
        return new EdgeRequest(id, "LOOP", "lidar-a", version, "kf1", "kf2",
                0.5, 0.0, 0.0, covariance, score, residual, requestId);
    }

    @Test
    void overrideThresholdAppliesOnlyToConfiguredVersion() {
        graphWriteService.writeKeyframe(keyframe("kf1", "req-kf1"));
        graphWriteService.writeKeyframe(keyframe("kf2", "req-kf2"));

        // 默认阈值（version=0）：score 0.60 < 0.75 → REJECTED
        GraphWriteService.EdgeWriteResult defaultVersion = graphWriteService.writeEdge(
                loop("loop-v0", 0, 0.60, 0.1, goodCovariance(), "req-loop-v0"));
        assertThat(defaultVersion.edge().status().name()).isEqualTo("REJECTED");
        assertThat(defaultVersion.edge().rejectReason()).contains("score");

        // 覆盖阈值（version=7）：score 0.60 >= 0.50、residual 1.0 <= 2.0 → VALID 且版本递增
        int versionBefore = jdbc.queryForObject(
                "SELECT current_version FROM graph_meta WHERE id=1", Integer.class);
        GraphWriteService.EdgeWriteResult overridden = graphWriteService.writeEdge(
                loop("loop-v7", 7, 0.60, 1.0, goodCovariance(), "req-loop-v7"));
        assertThat(overridden.edge().status().name()).isEqualTo("VALID");
        assertThat(overridden.edge().graphVersion()).isEqualTo(versionBefore + 1);

        // 覆盖阈值中的协方差迹 5.0 生效：迹 6.0 的噪声协方差在 v7 仍被拒绝
        GraphWriteService.EdgeWriteResult noisyAtV7 = graphWriteService.writeEdge(
                loop("loop-v7-noisy", 7, 0.90, 0.1, noisyCovariance(), "req-loop-v7-noisy"));
        assertThat(noisyAtV7.edge().status().name()).isEqualTo("REJECTED");
        assertThat(noisyAtV7.edge().rejectReason()).contains("trace");
        assertThat(noisyAtV7.edge().graphVersion()).isZero();
    }
}
