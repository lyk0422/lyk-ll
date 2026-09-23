package com.example.starter.slam.opt;

import java.util.List;

/**
 * 优化器输入中的一条有效边约束（相对位姿测量 + 6x6 协方差）。
 */
public record EdgeConstraint(
        String edgeId,
        String fromKeyframeId,
        String toKeyframeId,
        double dx,
        double dy,
        double dtheta,
        List<Double> covariance
) {
}
