package com.example.starter.slam.optim;

import com.example.starter.slam.config.LoopThresholdProperties;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 按数据版本选择阈值的回环可信度校验：分数、残差与协方差三项全部通过才可信。
 * 阈值为含边界比较；版本覆盖表中存在该版本时使用覆盖阈值，否则使用基线阈值。
 */
@Component
public class LoopClosurePolicy {

    /**
     * 3x3 信息矩阵展开后的元素个数。
     */
    private static final int MATRIX_SIZE = 9;

    private final LoopThresholdProperties properties;

    public LoopClosurePolicy(LoopThresholdProperties properties) {
        this.properties = properties;
    }

    /**
     * 校验回环约束。
     *
     * @param version     约束数据版本，用于选择阈值
     * @param score       匹配分数（0~1，越高越可信）
     * @param residual    初始残差
     * @param information 信息矩阵（3x3 按行展开）
     * @return 校验结果
     */
    public LoopCheckResult check(long version, Double score, Double residual, List<Double> information) {
        LoopThresholdProperties.Threshold threshold = thresholdFor(version);

        if (score == null) {
            return LoopCheckResult.rejected("loop closure score is required");
        }
        if (score < threshold.minScore()) {
            return LoopCheckResult.rejected("score " + score + " below minScore " + threshold.minScore()
                    + " for version " + version);
        }

        if (residual == null) {
            return LoopCheckResult.rejected("loop closure residual is required");
        }
        if (residual > threshold.maxResidual()) {
            return LoopCheckResult.rejected("residual " + residual + " above maxResidual " + threshold.maxResidual()
                    + " for version " + version);
        }

        if (information == null || information.size() != MATRIX_SIZE) {
            return LoopCheckResult.rejected("information matrix must contain 9 values (3x3)");
        }
        double covariance = covarianceOf(information);
        if (!Double.isFinite(covariance)) {
            return LoopCheckResult.rejected("information matrix is singular or non-finite");
        }
        if (covariance > threshold.maxCovariance()) {
            return LoopCheckResult.rejected("covariance " + covariance + " above maxCovariance "
                    + threshold.maxCovariance() + " for version " + version);
        }

        return LoopCheckResult.trusted();
    }

    /**
     * 选择版本对应阈值：精确版本覆盖优先，否则基线阈值。
     */
    private LoopThresholdProperties.Threshold thresholdFor(long version) {
        LoopThresholdProperties.Threshold override = properties.versionOverrides().get(version);
        if (override != null) {
            return override;
        }
        return new LoopThresholdProperties.Threshold(
                properties.minScore(), properties.maxResidual(), properties.maxCovariance());
    }

    /**
     * 以信息矩阵逆矩阵的首对角元素（首帧 x 方向方差）作为协方差标量。
     * 仅对 3x3 对称正定信息矩阵求逆，采用伴随矩阵法。
     */
    private double covarianceOf(List<Double> m) {
        double a = m.get(0);
        double b = m.get(1);
        double c = m.get(2);
        double d = m.get(3);
        double e = m.get(4);
        double f = m.get(5);
        double g = m.get(6);
        double h = m.get(7);
        double i = m.get(8);

        double det = a * (e * i - f * h)
                - b * (d * i - f * g)
                + c * (d * h - e * g);
        if (det == 0.0 || !Double.isFinite(det)) {
            return Double.NaN;
        }
        // 逆矩阵的 (0,0) 元素 = 余子式 (e*i - f*h) / det
        return (e * i - f * h) / det;
    }
}
