package com.example.starter.slam.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 版本化回环阈值配置。
 *
 * @param minScore        最低可信匹配分数（0~1，含边界）
 * @param maxResidual     最大允许初始残差（含边界）
 * @param maxCovariance   最大允许协方差（信息矩阵逆的首对角元素，含边界）
 * @param versionOverrides 按数据版本覆盖的阈值；高版本可使用更严阈值
 */
@ConfigurationProperties(prefix = "slam.loop")
public record LoopThresholdProperties(
        double minScore,
        double maxResidual,
        double maxCovariance,
        Map<Long, Threshold> versionOverrides
) {

    /**
     * 单个版本的阈值三元组。
     */
    public record Threshold(double minScore, double maxResidual, double maxCovariance) {
    }

    public LoopThresholdProperties {
        if (versionOverrides == null) {
            versionOverrides = new LinkedHashMap<>();
        }
    }
}
