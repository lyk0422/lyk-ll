package com.example.starter.support;

import org.springframework.stereotype.Component;

import java.util.concurrent.CyclicBarrier;

/**
 * 调节并发门控。
 *
 * <p>生产环境为空操作；并发测试可通过 {@link #setBarrierForTest} 临时设置屏障，
 * 在调节事务内“查询执行中任务前”和“插入新任务前”两次会合，
 * 以确定性地验证 active_station_id 唯一约束对并发下达的唯一受理。</p>
 */
@Component
public class AdjustmentGate {

    private volatile CyclicBarrier barrier;

    public void setBarrierForTest(CyclicBarrier barrier) {
        this.barrier = barrier;
    }

    /** 会合点：无屏障时立即返回；同一屏障可被循环使用两轮。 */
    public void rendezvous() {
        CyclicBarrier current = barrier;
        if (current != null) {
            try {
                current.await();
            } catch (Exception e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("并发门控等待失败", e);
            }
        }
    }
}
