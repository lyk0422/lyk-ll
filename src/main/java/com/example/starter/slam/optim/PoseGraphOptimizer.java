package com.example.starter.slam.optim;

/**
 * 可注入的确定性位姿图优化器。实现必须是确定性的：同一快照产生完全一致的输出。
 */
public interface PoseGraphOptimizer {

    /**
     * 对给定快照执行优化。
     *
     * @param snapshot 固定输入快照
     * @return 优化输出
     * @throws RuntimeException 优化失败时抛出，由队列判定为可重试失败
     */
    OptimizationOutput optimize(GraphSnapshot snapshot);
}
