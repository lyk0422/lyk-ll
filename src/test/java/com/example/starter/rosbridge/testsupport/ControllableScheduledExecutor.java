package com.example.starter.rosbridge.testsupport;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.Callable;
import java.util.concurrent.Delayed;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 测试用调度器：延迟任务与周期任务都只登记、不按墙钟自动执行，
 * 由测试通过 {@link #tick(long)}（推进虚拟时间并执行到期一次性任务）
 * 与 {@link #runPeriodicTasks()}（执行一次刷队列等周期任务）精确控制。
 */
public class ControllableScheduledExecutor extends AbstractExecutorService
        implements ScheduledExecutorService {

    private final AtomicLong sequence = new AtomicLong();
    private final List<Task> delayedTasks = new ArrayList<>();
    private final List<Runnable> periodicTasks = new ArrayList<>();
    private long virtualNowMs;
    private volatile boolean shutdown;

    @Override
    public synchronized ScheduledFuture<?> schedule(Runnable command, long delay, TimeUnit unit) {
        Task task = new Task(command, virtualNowMs + Math.max(0, unit.toMillis(delay)));
        delayedTasks.add(task);
        return task.future;
    }

    @Override
    public <V> ScheduledFuture<V> schedule(Callable<V> callable, long delay, TimeUnit unit) {
        throw new UnsupportedOperationException();
    }

    @Override
    public synchronized ScheduledFuture<?> scheduleAtFixedRate(Runnable command, long initialDelay,
                                                                long period, TimeUnit unit) {
        periodicTasks.add(command);
        return new DoneScheduledFuture<>();
    }

    @Override
    public ScheduledFuture<?> scheduleWithFixedDelay(Runnable command, long initialDelay,
                                                     long delay, TimeUnit unit) {
        periodicTasks.add(command);
        return new DoneScheduledFuture<>();
    }

    /** 推进虚拟时间 deltaMs，并按到期顺序执行所有已到期且未取消的延迟任务。 */
    public synchronized void tick(long deltaMs) {
        virtualNowMs += deltaMs;
        List<Task> due = new ArrayList<>();
        for (Task t : delayedTasks) {
            if (!t.future.cancelled.get() && t.deadlineMs <= virtualNowMs) {
                due.add(t);
            }
        }
        due.sort((a, b) -> Long.compare(a.deadlineMs, b.deadlineMs));
        delayedTasks.removeIf(t -> t.future.cancelled.get() || t.deadlineMs <= virtualNowMs);
        for (Task t : due) {
            t.command.run();
        }
    }

    /** 同步执行一次全部已登记的周期任务（如发送队列刷出）。 */
    public synchronized void runPeriodicTasks() {
        for (Runnable task : new ArrayList<>(periodicTasks)) {
            task.run();
        }
    }

    @Override
    public void shutdown() {
        shutdown = true;
        delayedTasks.clear();
        periodicTasks.clear();
    }

    @Override
    public List<Runnable> shutdownNow() {
        shutdown = true;
        List<Runnable> remaining = new ArrayList<>(delayedTasks.stream().map(t -> t.command).toList());
        delayedTasks.clear();
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

    private static final class Task {
        private final Runnable command;
        private final long deadlineMs;
        private final ManualFuture<?> future = new ManualFuture<>();

        private Task(Runnable command, long deadlineMs) {
            this.command = command;
            this.deadlineMs = deadlineMs;
        }
    }

    /** 周期任务返回的已完成 Future，cancel 仅影响其自身引用。 */
    private static final class DoneScheduledFuture<V> implements ScheduledFuture<V> {
        private final AtomicBoolean cancelled = new AtomicBoolean();

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
            return cancelled.compareAndSet(false, true);
        }

        @Override
        public boolean isCancelled() {
            return cancelled.get();
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

    private static final class ManualFuture<V> implements ScheduledFuture<V> {
        private final AtomicBoolean cancelled = new AtomicBoolean();

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
            return cancelled.compareAndSet(false, true);
        }

        @Override
        public boolean isCancelled() {
            return cancelled.get();
        }

        @Override
        public boolean isDone() {
            return cancelled.get();
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
