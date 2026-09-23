package com.example.starter.slam.optim;

import com.example.starter.slam.config.LoopThresholdProperties;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 版本化回环阈值校验的单元测试。
 */
class LoopClosurePolicyTest {

    /**
     * 对角信息矩阵 diag(w,w,w)，其逆首对角元素为 1/w。
     */
    private static List<Double> diagInformation(double w) {
        return List.of(w, 0.0, 0.0, 0.0, w, 0.0, 0.0, 0.0, w);
    }

    private LoopClosurePolicy policy() {
        Map<Long, LoopThresholdProperties.Threshold> overrides = new LinkedHashMap<>();
        overrides.put(2L, new LoopThresholdProperties.Threshold(0.85, 0.25, 0.05));
        return new LoopClosurePolicy(
                new LoopThresholdProperties(0.75, 0.5, 0.1, overrides));
    }

    @Test
    void trustedWhenAllMetricsWithinBaselineThreshold() {
        LoopCheckResult result = policy().check(1, 0.9, 0.1, diagInformation(100.0));
        assertTrue(result.isTrusted());
    }

    @Test
    void rejectedWhenScoreTooLow() {
        LoopCheckResult result = policy().check(1, 0.5, 0.1, diagInformation(100.0));
        assertFalse(result.isTrusted());
        assertTrue(result.getReason().contains("score"));
    }

    @Test
    void rejectedWhenResidualTooHigh() {
        LoopCheckResult result = policy().check(1, 0.9, 0.8, diagInformation(100.0));
        assertFalse(result.isTrusted());
        assertTrue(result.getReason().contains("residual"));
    }

    @Test
    void rejectedWhenCovarianceTooHigh() {
        // 单位信息矩阵的逆首对角元素为 1.0，大于基线 0.1
        LoopCheckResult result = policy().check(1, 0.9, 0.1, diagInformation(1.0));
        assertFalse(result.isTrusted());
        assertTrue(result.getReason().contains("covariance"));
    }

    @Test
    void rejectedWhenInformationMatrixMissingOrSingular() {
        LoopClosurePolicy p = policy();
        assertFalse(p.check(1, 0.9, 0.1, null).isTrusted());
        assertFalse(p.check(1, 0.9, 0.1, List.of(1.0, 0.0, 0.0)).isTrusted());
        // 全零矩阵不可逆
        assertFalse(p.check(1, 0.9, 0.1,
                List.of(0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0)).isTrusted());
    }

    @Test
    void rejectedWhenScoreOrResidualMissing() {
        LoopClosurePolicy p = policy();
        assertFalse(p.check(1, null, 0.1, diagInformation(100.0)).isTrusted());
        assertFalse(p.check(1, 0.9, null, diagInformation(100.0)).isTrusted());
    }

    @Test
    void versionTwoUsesStricterThreshold() {
        LoopClosurePolicy p = policy();
        // 0.8 分通过 v1 基线 0.75，但在 v2 严格阈值 0.85 下被拒绝
        assertFalse(p.check(2, 0.8, 0.1, diagInformation(100.0)).isTrusted());
        // 0.9 分、0.2 残差、协方差 0.01 在 v2 下通过
        assertTrue(p.check(2, 0.9, 0.2, diagInformation(100.0)).isTrusted());
        // 未配置覆盖的版本使用基线
        assertTrue(p.check(9, 0.8, 0.2, diagInformation(100.0)).isTrusted());
    }

    @Test
    void boundaryValuesAreInclusive() {
        // 恰好等于阈值边界时通过
        LoopClosurePolicy p = policy();
        assertTrue(p.check(1, 0.75, 0.5, diagInformation(10.0)).isTrusted());
    }
}
