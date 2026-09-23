package com.example.starter.slam.optim;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 确定性优化器的单元测试：同一快照结果逐位一致、简单里程计链能降低误差。
 */
class DeterministicPoseGraphOptimizerTest {

    private final DeterministicPoseGraphOptimizer optimizer = new DeterministicPoseGraphOptimizer();

    private GraphSnapshot chainSnapshot() {
        List<FrameSnapshot> frames = List.of(
                new FrameSnapshot("f0", "odom", 1, List.of(0.0, 0.0, 0.0)),
                new FrameSnapshot("f1", "odom", 1, List.of(1.0, 0.2, 0.0)),
                new FrameSnapshot("f2", "odom", 1, List.of(2.0, 0.0, 0.0)));
        List<EdgeSnapshot> edges = List.of(
                new EdgeSnapshot("e0", "odom", 1, com.example.starter.slam.domain.EdgeType.ODOMETRY,
                        "f0", "f1", List.of(1.0, 0.0, 0.0),
                        List.of(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0)),
                new EdgeSnapshot("e1", "odom", 1, com.example.starter.slam.domain.EdgeType.ODOMETRY,
                        "f1", "f2", List.of(1.0, 0.0, 0.0),
                        List.of(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0)));
        return new GraphSnapshot("g1", 3, frames, edges);
    }

    @Test
    void sameSnapshotProducesBitIdenticalOutput() {
        OptimizationOutput first = optimizer.optimize(chainSnapshot());
        OptimizationOutput second = optimizer.optimize(chainSnapshot());
        assertEquals(first.iterations(), second.iterations());
        assertEquals(first.cost(), second.cost(), 0.0);
        for (Map.Entry<String, List<Double>> entry : first.poses().entrySet()) {
            List<Double> a = entry.getValue();
            List<Double> b = second.poses().get(entry.getKey());
            for (int i = 0; i < a.size(); i++) {
                assertEquals(a.get(i), b.get(i), 0.0, "pose must be bit-identical for same snapshot");
            }
        }
    }

    @Test
    void emptyGraphYieldsEmptyOutput() {
        OptimizationOutput output = optimizer.optimize(
                new GraphSnapshot("g", 0, List.of(), List.of()));
        assertTrue(output.poses().isEmpty());
        assertEquals(0.0, output.cost(), 0.0);
    }

    @Test
    void optimizationReducesInitialErrorAndAnchorsFirstFrame() {
        GraphSnapshot snapshot = chainSnapshot();
        OptimizationOutput output = optimizer.optimize(snapshot);
        // 初始链上 f1 存在 0.2 的横向误差；优化后代价应明显低于初始残差能量
        double initialErrorEnergy = 0.2 * 0.2;
        assertTrue(output.cost() < initialErrorEnergy,
                "cost " + output.cost() + " should be below initial error energy " + initialErrorEnergy);
        // 首帧锚定，不被移动
        assertEquals(List.of(0.0, 0.0, 0.0), output.poses().get("f0"));
        assertNotEquals(List.of(1.0, 0.2, 0.0), output.poses().get("f1"));
    }
}
