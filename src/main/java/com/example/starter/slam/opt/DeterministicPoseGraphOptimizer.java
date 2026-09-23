package com.example.starter.slam.opt;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 默认确定性位姿图优化器：不依赖真实 SLAM 组件，采用固定迭代次数的高斯-赛德尔式
 * 位姿松弛。相同输入必然产生相同输出与相同 finalCost。
 */
public class DeterministicPoseGraphOptimizer implements PoseGraphOptimizer {

    private static final int MAX_ITERATIONS = 20;

    @Override
    public OptimizationOutput optimize(OptimizationInput input) {
        Map<String, double[]> poses = new LinkedHashMap<>();
        for (NodePose node : input.keyframes()) {
            poses.put(node.keyframeId(), new double[]{node.x(), node.y(), node.theta()});
        }
        double cost = 0.0;
        for (int iter = 0; iter < MAX_ITERATIONS; iter++) {
            cost = 0.0;
            for (EdgeConstraint edge : input.edges()) {
                double[] from = poses.get(edge.fromKeyframeId());
                double[] to = poses.get(edge.toKeyframeId());
                if (from == null || to == null) {
                    throw new RetryableOptimizationException("edge references missing keyframe in snapshot");
                }
                double predictedX = from[0] + edge.dx();
                double predictedY = from[1] + edge.dy();
                double predictedTheta = from[2] + edge.dtheta();
                double ex = predictedX - to[0];
                double ey = predictedY - to[1];
                double et = predictedTheta - to[2];
                cost += ex * ex + ey * ey + et * et;
                // 固定步长松弛，保证确定性且无外部库依赖
                to[0] += 0.5 * ex;
                to[1] += 0.5 * ey;
                to[2] += 0.5 * et;
            }
        }
        return new OptimizationOutput(poses, cost, MAX_ITERATIONS);
    }
}
