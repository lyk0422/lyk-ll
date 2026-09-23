package com.example.starter.rosbridge.ws;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.Callable;
import java.util.concurrent.Delayed;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 测试用调度器：{@code scheduleAtFixedRate} 只登记任务不自动执行，
 * 测试通过 {@link #runPeriodicTasks()} 手动触发，保证队列刷出时机完全可控。
 */
public class ManualScheduledExecutor extends AbstractExecutorService
        implements ScheduledExecutorService {

    private final List<Runnable> periodicTasks = new ArrayList<>();
    private volatile boolean shutdown;

    @Override
    public ScheduledFuture<?> schedule(Runnable command, long delay, TimeUnit unit) {
        throw new UnsupportedOperationException();
    }

    @Override
    public <V> ScheduledFuture<V> schedule(Callable<V> callable, long delay, TimeUnit unit) {
        throw new UnsupportedOperationException();
    }

    @Override
    public ScheduledFuture<?> scheduleAtFixedRate(Runnable command, long initialDelay,
                                                  long period, TimeUnit unit) {
        periodicTasks.add(command);
        return new CompletedScheduledFuture<>();
    }

    @Override
    public ScheduledFuture<?> scheduleWithFixedDelay(Runnable command, long initialDelay,
                                                     long delay, TimeUnit unit) {
        periodicTasks.add(command);
        return new CompletedScheduledFuture<>();
    }

    /** 同步执行一次全部已登记周期任务。 */
    public void runPeriodicTasks() {
        for (Runnable task : new ArrayList<>(periodicTasks)) {
            task.run();
        }
    }

    @Override
    public void shutdown() {
        shutdown = true;
        periodicTasks.clear();
    }

    @Override
    public List<Runnable> shutdownNow() {
        shutdown = true;
        List<Runnable> remaining = new ArrayList<>(periodicTasks);
        periodicTasks.clear();
        return remaining;
    }

    @Override
    public boolean isShutdown() {
        return shutdown;
    }

    @Override
    public boolean isTerminated() {
        return shutdown;
    }

    @Override
    public boolean awaitTermination(long timeout, TimeUnit unit) {
        return true;
    }

    @Override
    public void execute(Runnable command) {
        command.run();
    }

    private static final class CompletedScheduledFuture<V> implements ScheduledFuture<V> {
        private final AtomicReference<Boolean> canceled = new AtomicReference<>(false);

        @Override
        public long getDelay(TimeUnit unit) {
            return 0;
        }

        @Override
        public int compareTo(Delayed o) {
            return 0;
        }

        @Override
        public boolean cancel(boolean mayInterruptIfRunning) {
            return canceled.compareAndSet(false, true);
        }

        @Override
        public boolean isCancelled() {
            return canceled.get();
        }

        @Override
        public boolean isDone() {
            return true;
        }

        @Override
        public V get() {
            return null;
        }

        @Override
        public V get(long timeout, TimeUnit unit) {
            return null;
        }
    }
}
