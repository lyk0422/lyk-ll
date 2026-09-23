package com.example.starter.audit.domain;

/**
 * 可注入的阶段执行器：模拟感知、定位、规划、控制，不依赖真实自动驾驶栈。
 * 实现必须保证相同绑定版本、随机种子与上游产物摘要下输出确定。
 */
public interface StageExecutor {

    /**
     * 执行单个阶段。
     *
     * @param context 阶段上下文（绑定版本、种子、上游摘要等）
     * @return 阶段产物（指标、合成产物、可重放命令）
     */
    StageProduct execute(StageContext context)
            throws StageFailureException, StageInterruptedException;
}
