package com.example.starter.slam.queue;

import com.example.starter.slam.domain.TaskStatus;
import com.example.starter.slam.optim.GraphSnapshot;
import com.example.starter.slam.optim.OptimizationOutput;
import com.example.starter.slam.optim.PoseGraphOptimizer;
import com.example.starter.slam.optim.SnapshotCodec;
import com.example.starter.slam.optim.SnapshotFactory;
import com.example.starter.slam.po.OptimizeResultPO;
import com.example.starter.slam.po.OptimizeTaskPO;
import com.example.starter.slam.store.GraphStore;
import com.example.starter.slam.store.TaskStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 异步优化任务队列：固定数量工作线程领取 PENDING 任务并执行可注入优化器。
 *
 * <ul>
 *   <li>任务首次执行时固化当前图快照；显式重试复用同一快照，不重新读图；</li>
 *   <li>优化异常记为可重试失败，保留快照摘要、原因与尝试次数；</li>
 *   <li>完成时进行结果版本保护：已存在基于更高图版本（更多有效边）的成功结果时，
 *       旧任务标记 SUPERSEDED 且不写入结果。</li>
 * </ul>
 */
@Component
public class OptimizationQueue {

    private static final Logger log = LoggerFactory.getLogger(OptimizationQueue.class);

    private final TaskStore taskStore;
    private final GraphStore graphStore;
    private final PoseGraphOptimizer optimizer;
    private final SnapshotFactory snapshotFactory;
    private final TransactionTemplate transactionTemplate;
    private final ObjectMapper objectMapper;
    private final SnapshotCodec snapshotCodec;
    private final long pollMillis;
    private final int workerCount;
    private final boolean enabled;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final Object signalLock = new Object();
    private final List<Thread> workers = new ArrayList<>();

    public OptimizationQueue(TaskStore taskStore, GraphStore graphStore, PoseGraphOptimizer optimizer,
                             SnapshotFactory snapshotFactory, SnapshotCodec snapshotCodec,
                             org.springframework.transaction.PlatformTransactionManager transactionManager,
                             ObjectMapper objectMapper,
                             @Value("${slam.queue.poll-millis:50}") long pollMillis,
                             @Value("${slam.queue.workers:2}") int workerCount,
                             @Value("${slam.queue.enabled:true}") boolean enabled) {
        this.taskStore = taskStore;
        this.graphStore = graphStore;
        this.optimizer = optimizer;
        this.snapshotFactory = snapshotFactory;
        this.snapshotCodec = snapshotCodec;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.objectMapper = objectMapper;
        this.pollMillis = pollMillis;
        this.workerCount = workerCount;
        this.enabled = enabled;
    }

    /**
     * 通知工作线程有待执行任务（提交/重试后唤醒，降低延迟）。
     */
    public void signal() {
        synchronized (signalLock) {
            signalLock.notifyAll();
        }
    }

    @PostConstruct
    void start() {
        if (!enabled) {
            log.info("optimization queue auto-workers disabled (slam.queue.enabled=false)");
            return;
        }
        if (!running.compareAndSet(false, true)) {
            return;
        }
        for (int i = 0; i < workerCount; i++) {
            Thread worker = new Thread(this::runLoop, "slam-optimizer-" + i);
            worker.setDaemon(true);
            worker.start();
            workers.add(worker);
        }
    }

    @PreDestroy
    void stop() {
        running.set(false);
        signal();
        for (Thread worker : workers) {
            try {
                worker.join(2000);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private void runLoop() {
        while (running.get()) {
            try {
                boolean processed = processOne();
                if (!processed) {
                    synchronized (signalLock) {
                        if (running.get()) {
                            signalLock.wait(pollMillis);
                        }
                    }
                }
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception ex) {
                // 单轮异常不能杀死工作线程
                log.warn("optimization worker iteration failed: {}", ex.getMessage(), ex);
            }
        }
    }

    /**
     * 领取并执行一个任务。
     *
     * @return 是否领取到任务
     */
    public boolean processOne() {
        Instant claimedAt = Instant.now();
        OptimizeTaskPO task = taskStore.claimNext(claimedAt);
        if (task == null) {
            return false;
        }
        execute(task.taskId(), task.attempts());
        return true;
    }

    /**
     * 执行已处于 RUNNING 的任务：固化/复用快照、调用优化器、落盘最终状态。
     */
    public void execute(String taskId, int attempts) {
        OptimizeTaskPO runningTask = taskStore.findTask(taskId);
        if (runningTask == null || runningTask.status() != TaskStatus.RUNNING) {
            return;
        }

        GraphSnapshot snapshot;
        if (runningTask.snapshotJson() != null) {
            // 显式重试必须使用原快照
            snapshot = snapshotCodec.read(runningTask.snapshotJson());
        } else {
            snapshot = snapshotFactory.build(runningTask.graphId());
            taskStore.attachSnapshot(taskId, snapshotCodec.write(snapshot), snapshot.summary(), Instant.now());
        }

        try {
            OptimizationOutput output = optimizer.optimize(snapshot);
            completeWithVersionProtection(taskId, attempts, snapshot, output);
        } catch (RuntimeException ex) {
            log.info("optimization task {} failed (attempt {}): {}", taskId, attempts, ex.getMessage());
            taskStore.markFailedRetryable(taskId, attempts,
                    truncate(ex.getClass().getSimpleName() + ": " + ex.getMessage()), Instant.now());
        }
    }

    /**
     * 结果版本保护：完成时若已存在基于更高图版本（更多有效边）的成功结果，
     * 本旧结果标记 SUPERSEDED，不写入结果表，不能覆盖新结果。
     */
    private void completeWithVersionProtection(String taskId, int attempts,
                                               GraphSnapshot snapshot, OptimizationOutput output) {
        transactionTemplate.executeWithoutResult(txStatus -> {
            OptimizeTaskPO locked = taskStore.lockTask(taskId);
            if (locked == null || locked.status() != TaskStatus.RUNNING) {
                return;
            }
            OptimizeResultPO latest = taskStore.findLatestSucceededResult(snapshot.graphId());
            if (latest != null && latest.graphVersion() > snapshot.graphVersion()) {
                taskStore.markSuperseded(taskId, attempts, Instant.now());
                log.info("task {} superseded by result of graph version {} (snapshot version {})",
                        taskId, latest.graphVersion(), snapshot.graphVersion());
                return;
            }

            Instant now = Instant.now();
            List<String> frameIds = snapshot.frames().stream().map(f -> f.id()).toList();
            List<List<Double>> orderedPoses = frameIds.stream()
                    .map(id -> output.poses().getOrDefault(id, List.of(0.0, 0.0, 0.0)))
                    .toList();
            OptimizeResultPO result = new OptimizeResultPO(
                    UUID.randomUUID().toString(),
                    taskId,
                    snapshot.graphId(),
                    snapshot.graphVersion(),
                    writeJson(frameIds),
                    writeJson(orderedPoses),
                    output.cost(),
                    output.iterations(),
                    now);
            taskStore.insertResult(result);
            taskStore.markSucceeded(taskId, snapshot.graphVersion(), attempts, now);
        });
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception ex) {
            throw new IllegalStateException("failed to serialize optimization output", ex);
        }
    }

    private String truncate(String reason) {
        if (reason == null) {
            return "unknown failure";
        }
        return reason.length() > 2000 ? reason.substring(0, 2000) : reason;
    }
}
