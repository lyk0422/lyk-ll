package com.example.starter.slam.support;

import com.example.starter.slam.opt.EdgeConstraint;
import com.example.starter.slam.opt.NodePose;
import com.example.starter.slam.opt.OptimizationInput;
import com.example.starter.slam.opt.OptimizationOutput;
import com.example.starter.slam.opt.PoseGraphOptimizer;
import com.example.starter.slam.opt.RetryableOptimizationException;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 测试用脚本化确定性优化器：
 * <ul>
 *   <li>门闩与失败脚本均为一次性，仅作用于下一次调用，便于确定性地构造并发交错；</li>
 *   <li>每次调用记录输入摘要，供断言重试使用的是原快照；</li>
 *   <li>成功输出确定性：位姿保持先验值，代价 = 版本 + 去重边数 × 0.01。</li>
 * </ul>
 */
public class ScriptedOptimizer implements PoseGraphOptimizer {

    /**
     * 单次调用门闩：进入 optimize 后 countDown entered，随后阻塞至 release 释放。
     */
    public record Gate(CountDownLatch entered, CountDownLatch release) {
    }

    private final AtomicInteger calls = new AtomicInteger();
    private final AtomicReference<RuntimeException> failure = new AtomicReference<>();
    private final AtomicReference<Gate> gate = new AtomicReference<>();
    private final List<String> seenDigests =
            java.util.Collections.synchronizedList(new java.util.ArrayList<>());

    /**
     * 下一次 optimize 调用抛出可重试异常（仅一次）。
     */
    public void failNextCallWith(String message) {
        failure.set(new RetryableOptimizationException(message));
    }

    /**
     * 下一次 optimize 调用进入门闩阻塞（仅一次）。
     */
    public void blockNextCall(CountDownLatch entered, CountDownLatch release) {
        gate.set(new Gate(entered, release));
    }

    public int getCallCount() {
        return calls.get();
    }

    public List<String> getSeenDigests() {
        synchronized (seenDigests) {
            return List.copyOf(seenDigests);
        }
    }

    /**
     * 复位调用计数、门闩、失败脚本与已记录摘要。
     */
    public void reset() {
        calls.set(0);
        failure.set(null);
        gate.set(null);
        seenDigests.clear();
    }

    @Override
    public OptimizationOutput optimize(OptimizationInput input) {
        calls.incrementAndGet();
        seenDigests.add(input.snapshotDigest());

        Gate g = gate.getAndSet(null);
        if (g != null) {
            g.entered().countDown();
            try {
                if (!g.release().await(10, TimeUnit.SECONDS)) {
                    throw new RetryableOptimizationException("timed out waiting at test gate");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RetryableOptimizationException("interrupted while gated", e);
            }
        }

        RuntimeException currentFailure = failure.getAndSet(null);
        if (currentFailure != null) {
            throw currentFailure;
        }

        Map<String, double[]> poses = new LinkedHashMap<>();
        for (NodePose node : input.keyframes()) {
            poses.put(node.keyframeId(), new double[]{node.x(), node.y(), node.theta()});
        }
        int edgeCount = (int) input.edges().stream().map(EdgeConstraint::edgeId).distinct().count();
        double cost = input.graphVersion() + edgeCount * 0.01;
        return new OptimizationOutput(poses, cost, edgeCount);
    }
}
