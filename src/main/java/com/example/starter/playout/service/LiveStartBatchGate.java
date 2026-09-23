package com.example.starter.playout.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * 同一频道、同一开始时刻的直播插播并发请求协调器。
 *
 * <p>数据库行锁只能把并发事务串行化，先提交者无法得知稍后到达的同刻请求；
 * 本组件在内存中把短窗口内同键（channel + start）到达的请求收集为一个批次，
 * 按业务裁决顺序（优先级降序、requestId 字典序升序）依次执行各自独立事务，
 * 从而让“同一时刻并发插播唯一胜者”的结果与返回状态确定且一致。</p>
 *
 * <p>错开超过收集窗口的请求视为普通顺序请求，按正常抢占语义处理；
 * 本组件只在本地进程内协调，不连接任何外部设备。</p>
 */
@Component
public class LiveStartBatchGate {

    /** 同刻并发请求的收集窗口（毫秒）；仅批次首个请求承担该等待。 */
    private final long gatherWindowMillis;

    private final ConcurrentHashMap<String, Batch> batches = new ConcurrentHashMap<>();

    public LiveStartBatchGate(
            @Value("${playout.live-start.gather-window-millis:50}") long gatherWindowMillis) {
        this.gatherWindowMillis = gatherWindowMillis;
    }

    /**
     * 以批次方式执行同刻请求；方法返回时本请求的动作已完成。
     *
     * @param key        批次键（频道 + 开始时刻）
     * @param comparator 批内排序：更优者先执行
     * @param order      本请求的排序凭据
     * @param action     在独立事务中执行的动作
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    public <C, T> T runBatched(String key, Comparator<C> comparator, C order, Supplier<T> action)
            throws InterruptedException {
        Batch batch = batches.computeIfAbsent(key, k -> new Batch());
        Member self = new Member(order, action);
        boolean leader;
        boolean late;
        synchronized (batch) {
            late = batch.closed;
            leader = !late && batch.members.isEmpty();
            if (!late) {
                batch.members.add(self);
            }
        }
        if (late) {
            // 收集窗口已关闭：作为普通顺序请求在自己的事务中执行，不等待批次。
            return action.get();
        }
        if (leader) {
            // 队长等待一个收集窗口，让同时刻并发的其他请求入批。
            TimeUnit.MILLISECONDS.sleep(gatherWindowMillis);
            List<Member<C, T>> members;
            synchronized (batch) {
                members = new ArrayList<>(batch.members);
                batch.closed = true;
                batch.notifyAll();
            }
            members.sort((a, b) -> comparator.compare(a.order, b.order));
            try {
                for (Member<C, T> member : members) {
                    try {
                        member.result = member.action.get();
                    } catch (RuntimeException e) {
                        member.error = e;
                    }
                }
            } finally {
                members.forEach(m -> m.done.countDown());
                batches.remove(key, batch);
            }
        } else {
            // 队员等待队长关闭收集窗口，随后由队长统一顺序执行；再等待自己的结果。
            long deadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            synchronized (batch) {
                while (!batch.closed) {
                    long remainingMillis = TimeUnit.NANOSECONDS.toMillis(deadlineNanos - System.nanoTime());
                    if (remainingMillis <= 0) {
                        throw new IllegalStateException("live start batch gather timeout");
                    }
                    batch.wait(remainingMillis);
                }
            }
        }
        if (!self.done.await(10, TimeUnit.SECONDS)) {
            throw new IllegalStateException("live start batch execution timeout");
        }
        if (self.error != null) {
            throw self.error;
        }
        @SuppressWarnings("unchecked")
        T result = (T) self.result;
        return result;
    }

    /**
     * 批次；成员在收集窗口内累积，队长关闭后按顺序统一执行。
     */
    private static final class Batch<C, T> {
        private final List<Member<C, T>> members = new ArrayList<>();
        private volatile boolean closed;
    }

    /**
     * 批内成员。
     */
    private static final class Member<C, T> {
        private final C order;
        private final Supplier<T> action;
        private final java.util.concurrent.CountDownLatch done =
                new java.util.concurrent.CountDownLatch(1);
        private volatile T result;
        private volatile RuntimeException error;

        private Member(C order, Supplier<T> action) {
            this.order = order;
            this.action = action;
        }
    }
}
