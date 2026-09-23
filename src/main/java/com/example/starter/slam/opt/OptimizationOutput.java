package com.example.starter.slam.opt;

import java.util.Map;

/**
 * 优化器输出：优化后位姿（关键帧 ID -> [x, y, theta]）与终止代价。
 */
public record OptimizationOutput(
        Map<String, double[]> poses,
        double finalCost,
        int iterations
) {
}
