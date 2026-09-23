package com.example.starter.slam.testsupport;

import com.example.starter.slam.optim.DeterministicPoseGraphOptimizer;
import com.example.starter.slam.optim.GraphSnapshot;
import com.example.starter.slam.optim.OptimizationOutput;
import com.example.starter.slam.optim.PoseGraphOptimizer;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 测试用可控优化器：默认委托确定性优化器；可编排前 N 次失败，或在优化入口阻塞以稳定复现
 * "运行中出现新版本"的竞态窗口。
 */
public class ControllableOptimizer implements PoseGraphOptimizer {

    private final DeterministicPoseGraphOptimizer delegate = new DeterministicPoseGraphOptimizer();
    private final AtomicInteger failRemaining = new AtomicInteger(0);
    private volatile CountDownLatch entered;
    private volatile CountDownLatch release;
    private final AtomicInteger invocationCount = new AtomicInteger(0);

    /**
     * 接下来 {@code times} 次调用抛出可重试异常，之后恢复正常。
     */
    public void failNext(int times) {
        failRemaining.set(times);
    }

    /**
     * 在优化入口阻塞，直到调用 {@link #releaseBlock()}。
     */
    public void blockDuringOptimization() {
        this.entered = new CountDownLatch(1);
        this.release = new CountDownLatch(1);
    }

    public void awaitEntered() throws InterruptedException {
        if (entered != null) {
            entered.await();
        }
    }

    public void releaseBlock() {
        if (release != null) {
            release.countDown();
        }
    }

    public int invocationCount() {
        return invocationCount.get();
    }

    public void reset() {
        failRemaining.set(0);
        entered = null;
        release = null;
        invocationCount.set(0);
    }

    @Override
    public OptimizationOutput optimize(GraphSnapshot snapshot) {
        invocationCount.incrementAndGet();
        if (entered != null) {
            entered.countDown();
            try {
                release.await();
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("interrupted while blocked in test optimizer", ex);
            }
        }
        if (failRemaining.getAndUpdate(current -> Math.max(0, current - 1)) > 0) {
            throw new OptimizationTestException("simulated transient optimization failure");
        }
        return delegate.optimize(snapshot);
    }

    /**
     * 模拟优化器抛出的运行时异常。
     */
    public static class OptimizationTestException extends RuntimeException {
        public OptimizationTestException(String message) {
            super(message);
        }
    }
}
