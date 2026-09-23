package com.example.starter.slam.optim;

import java.util.List;
import java.util.Map;

/**
 * 优化输出。
 *
 * @param poses      关键帧 ID 到优化位姿 [x, y, theta] 的映射
 * @param cost       终止代价
 * @param iterations 实际迭代次数
 */
public record OptimizationOutput(
        Map<String, List<Double>> poses,
        double cost,
        int iterations
) {
}
