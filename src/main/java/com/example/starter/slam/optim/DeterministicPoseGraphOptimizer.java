package com.example.starter.slam.optim;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 确定性的本地位姿图优化器。
 *
 * <p>不依赖真实 SLAM 求解器：以关键帧初始位姿为起点，锚定按 ID 排序的首个关键帧，
 * 对全部有效边执行固定次数的确定性松弛迭代（误差均分到两端，角度做 [-pi,pi) 包裹），
 * 最终代价为信息矩阵对角加权的相对位姿误差平方和。同一快照必然产生逐位一致的输出。</p>
 */
@Component
public class DeterministicPoseGraphOptimizer implements PoseGraphOptimizer {

    /**
     * 固定松弛迭代次数，保证确定性与有界执行时间。
     */
    private static final int ITERATIONS = 20;

    @Override
    public OptimizationOutput optimize(GraphSnapshot snapshot) {
        List<FrameSnapshot> frames = snapshot.frames();
        List<EdgeSnapshot> edges = snapshot.edges();
        if (frames.isEmpty()) {
            return new OptimizationOutput(Map.of(), 0.0, 0);
        }

        // 按帧 ID 固定顺序初始化 x, y, theta
        int n = frames.size();
        Map<String, Integer> index = new LinkedHashMap<>();
        double[] x = new double[n];
        double[] y = new double[n];
        double[] theta = new double[n];
        for (int k = 0; k < n; k++) {
            FrameSnapshot frame = frames.get(k);
            index.put(frame.id(), k);
            List<Double> pose = frame.pose();
            x[k] = pose.get(0);
            y[k] = pose.get(1);
            theta[k] = pose.get(2);
        }

        int anchor = 0;
        int actualIterations = 0;
        for (int iter = 0; iter < ITERATIONS; iter++) {
            actualIterations++;
            for (EdgeSnapshot edge : edges) {
                Integer fi = index.get(edge.fromFrameId());
                Integer ti = index.get(edge.toFrameId());
                if (fi == null || ti == null) {
                    // 快照内边必然引用存在的帧；防御性跳过
                    continue;
                }
                List<Double> z = edge.measurement();
                double mx = z.get(0);
                double my = z.get(1);
                double mt = wrap(z.get(2));

                // 由当前绝对位姿预测的相对位姿
                double dx = x[ti] - x[fi];
                double dy = y[ti] - y[fi];
                double predictedX = Math.cos(theta[fi]) * dx + Math.sin(theta[fi]) * dy;
                double predictedY = -Math.sin(theta[fi]) * dx + Math.cos(theta[fi]) * dy;

                double ex = mx - predictedX;
                double ey = my - predictedY;
                double et = wrap(mt - wrap(theta[ti] - theta[fi]));

                // 误差均分到两端（锚点不移动）
                double step = 0.5;
                if (fi != anchor) {
                    x[fi] -= step * (Math.cos(theta[fi]) * ex - Math.sin(theta[fi]) * ey);
                    y[fi] -= step * (Math.sin(theta[fi]) * ex + Math.cos(theta[fi]) * ey);
                    theta[fi] = wrap(theta[fi] - step * et);
                }
                if (ti != anchor) {
                    x[ti] += step * (Math.cos(theta[fi]) * ex - Math.sin(theta[fi]) * ey);
                    y[ti] += step * (Math.sin(theta[fi]) * ex + Math.cos(theta[fi]) * ey);
                    theta[ti] = wrap(theta[ti] + step * et);
                }
            }
        }

        double cost = totalCost(edges, index, x, y, theta);

        Map<String, List<Double>> poses = new LinkedHashMap<>();
        for (int k = 0; k < n; k++) {
            poses.put(frames.get(k).id(), List.of(x[k], y[k], theta[k]));
        }
        return new OptimizationOutput(poses, cost, actualIterations);
    }

    /**
     * 信息矩阵对角加权（取平移与旋转三个对角权重）的相对位姿误差平方和。
     */
    private double totalCost(List<EdgeSnapshot> edges, Map<String, Integer> index,
                             double[] x, double[] y, double[] theta) {
        double cost = 0.0;
        for (EdgeSnapshot edge : edges) {
            Integer fi = index.get(edge.fromFrameId());
            Integer ti = index.get(edge.toFrameId());
            if (fi == null || ti == null) {
                continue;
            }
            List<Double> z = edge.measurement();
            double dx = x[ti] - x[fi];
            double dy = y[ti] - y[fi];
            double predictedX = Math.cos(theta[fi]) * dx + Math.sin(theta[fi]) * dy;
            double predictedY = -Math.sin(theta[fi]) * dx + Math.cos(theta[fi]) * dy;

            double ex = z.get(0) - predictedX;
            double ey = z.get(1) - predictedY;
            double et = wrap(z.get(2) - wrap(theta[ti] - theta[fi]));

            List<Double> info = edge.information();
            double w0 = info != null && info.size() > 0 ? info.get(0) : 1.0;
            double w4 = info != null && info.size() > 4 ? info.get(4) : 1.0;
            double w8 = info != null && info.size() > 8 ? info.get(8) : 1.0;
            cost += w0 * ex * ex + w4 * ey * ey + w8 * et * et;
        }
        return cost;
    }

    /**
     * 角度包裹到 [-pi, pi)。
     */
    private double wrap(double angle) {
        double wrapped = (angle + Math.PI) % (2.0 * Math.PI);
        if (wrapped < 0) {
            wrapped += 2.0 * Math.PI;
        }
        return wrapped - Math.PI;
    }
}
