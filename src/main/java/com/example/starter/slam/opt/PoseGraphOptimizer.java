package com.example.starter.slam.opt;

/**
 * 可注入的位姿图优化器。实现必须确定性：相同 {@link OptimizationInput} 产生相同输出。
 */
public interface PoseGraphOptimizer {

    /**
     * 对给定快照执行位姿图优化。
     *
     * @throws RetryableOptimizationException 优化失败但可使用同一快照重试
     */
    OptimizationOutput optimize(OptimizationInput input);
}
