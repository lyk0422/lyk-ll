package com.example.starter.slam.service;

import com.example.starter.slam.domain.OptimizationResult;
import com.example.starter.slam.domain.OptimizationTask;
import com.example.starter.slam.model.TaskStatus;
import com.example.starter.slam.opt.OptimizationInput;
import com.example.starter.slam.opt.OptimizationOutput;
import com.example.starter.slam.opt.PoseGraphOptimizer;
import com.example.starter.slam.opt.RetryableOptimizationException;
import com.example.starter.slam.repo.EdgeRepository;
import com.example.starter.slam.repo.GraphMetaRepository;
import com.example.starter.slam.repo.KeyframeRepository;
import com.example.starter.slam.repo.OptimizationResultRepository;
import com.example.starter.slam.repo.OptimizationTaskRepository;
import com.example.starter.slam.web.ConflictException;
import com.example.starter.slam.web.NotFoundException;
import com.example.starter.slam.web.UnprocessableException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.Executor;

/**
 * 优化任务服务：请求判重/合并、异步执行、失败重试、结果版本保护。
 */
@Service
public class OptimizationService {

    private static final Logger log = LoggerFactory.getLogger(OptimizationService.class);

    private final OptimizationTaskRepository taskRepository;
    private final OptimizationResultRepository resultRepository;
    private final GraphMetaRepository graphMetaRepository;
    private final KeyframeRepository keyframeRepository;
    private final EdgeRepository edgeRepository;
    private final SnapshotBuilder snapshotBuilder;
    private final PoseGraphOptimizer optimizer;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final Executor executor;
    private final TransactionTemplate txTemplate;
    private final boolean autoDispatch;

    public OptimizationService(OptimizationTaskRepository taskRepository,
                               OptimizationResultRepository resultRepository,
                               GraphMetaRepository graphMetaRepository,
                               KeyframeRepository keyframeRepository,
                               EdgeRepository edgeRepository,
                               SnapshotBuilder snapshotBuilder,
                               PoseGraphOptimizer optimizer,
                               ObjectMapper objectMapper,
                               Clock clock,
                               PlatformTransactionManager transactionManager,
                               com.example.starter.slam.config.SlamProperties properties,
                               @Qualifier("slamOptExecutor") Executor executor) {
        this.taskRepository = taskRepository;
        this.resultRepository = resultRepository;
        this.graphMetaRepository = graphMetaRepository;
        this.keyframeRepository = keyframeRepository;
        this.edgeRepository = edgeRepository;
        this.snapshotBuilder = snapshotBuilder;
        this.optimizer = optimizer;
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.executor = executor;
        this.autoDispatch = properties.getOpt().isAutoDispatch();
        this.txTemplate = new TransactionTemplate(transactionManager);
    }

    /**
     * 提交优化请求：
     * <ul>
     *   <li>同 requestId 重复提交返回原任务（幂等）；requestId 已被图写入占用返回 409；</li>
     *   <li>存在尚未开始的 PENDING 任务时合并：新请求置 MERGED 并指向保留任务，
     *       保留任务的目标版本提升为最高图版本并重建快照；</li>
     *   <li>否则创建新的 PENDING 任务，提交事务提交后异步触发执行。</li>
     * </ul>
     */
    @Transactional
    public OptimizationTask submit(String requestId) {
        OptimizationTask existing = taskRepository.findByRequestId(requestId);
        if (existing != null) {
            return existing;
        }
        ensureRequestIdNotUsedByGraph(requestId);

        // 行锁串行化提交：与图写入及其他提交互斥，保证目标版本与合并判定一致
        int currentVersion = graphMetaRepository.lockAndGetCurrentVersion();
        if (currentVersion == 0) {
            throw new UnprocessableException("graph is empty: write keyframes before requesting optimization");
        }

        List<OptimizationTask> pending = taskRepository.findPendingForMerge();
        long taskId;
        if (!pending.isEmpty()) {
            // 合并进最早的待执行任务；保留任务覆盖最高版本
            taskId = insertMergedRequest(requestId, currentVersion, Instant.now(clock), pending);
        } else {
            SnapshotBuilder.SnapshotData snapshot = snapshotBuilder.build(currentVersion);
            taskId = taskRepository.insert(new OptimizationTask(
                    null, requestId, currentVersion, TaskStatus.PENDING,
                    snapshot.json(), snapshot.digest(), null, 0, null, Instant.now(clock), null, null));
        }
        OptimizationTask created = taskRepository.findById(taskId);
        afterCommit(this::scheduleDispatch);
        return created;
    }

    /**
     * 插入 MERGED 请求，并把保留任务提升到覆盖最高图版本（含当前版本与其他待合并请求的目标版本）。
     */
    private long insertMergedRequest(String requestId, int currentVersion, Instant now,
                                     List<OptimizationTask> pending) {
        OptimizationTask survivor = pending.get(0);
        int highest = Math.max(currentVersion,
                pending.stream().mapToInt(OptimizationTask::targetGraphVersion).max().orElse(currentVersion));

        long mergedId = taskRepository.insert(new OptimizationTask(
                null, requestId, highest, TaskStatus.MERGED,
                null, null, survivor.id(), 0, null, now, null, null));

        if (highest > survivor.targetGraphVersion()) {
            SnapshotBuilder.SnapshotData snapshot = snapshotBuilder.build(highest);
            taskRepository.updateTargetVersion(survivor.id(), highest, snapshot.json(), snapshot.digest());
        }
        return mergedId;
    }

    /**
     * 查询请求对应的任务；MERGED 任务通过 mergedIntoId 保留对最终执行任务的查询关系。
     */
    @Transactional(readOnly = true)
    public OptimizationTask getByRequestId(String requestId) {
        OptimizationTask task = taskRepository.findByRequestId(requestId);
        if (task == null) {
            throw new NotFoundException("optimization request '" + requestId + "' not found");
        }
        return task;
    }

    @Transactional(readOnly = true)
    public OptimizationTask getTask(long taskId) {
        OptimizationTask task = taskRepository.findById(taskId);
        if (task == null) {
            throw new NotFoundException("optimization task " + taskId + " not found");
        }
        return task;
    }

    @Transactional(readOnly = true)
    public OptimizationResult getResult(long taskId) {
        OptimizationTask task = getTask(taskId);
        OptimizationResult result = findResultForTask(task);
        if (result == null) {
            throw new NotFoundException("no optimization result for task " + taskId
                    + " (status " + task.status() + ")");
        }
        return result;
    }

    /**
     * 查找任务自身结果；MERGED 任务的结果归属其最终执行任务。任务无结果时返回 null。
     */
    @Transactional(readOnly = true)
    public OptimizationResult findResultForTask(OptimizationTask task) {
        OptimizationResult result = resultRepository.findByTaskId(task.id());
        if (result == null && task.mergedIntoId() != null) {
            result = resultRepository.findByTaskId(task.mergedIntoId());
        }
        return result;
    }

    /**
     * 对 RETRYABLE_FAILED 任务使用原快照重试；重试次数 +1。其他状态不允许重试。
     */
    @Transactional
    public OptimizationTask retry(long taskId) {
        OptimizationTask task = taskRepository.findById(taskId);
        if (task == null) {
            throw new NotFoundException("optimization task " + taskId + " not found");
        }
        if (task.status() != TaskStatus.RETRYABLE_FAILED) {
            throw new ConflictException("task " + taskId + " is in status " + task.status()
                    + ", only RETRYABLE_FAILED tasks can be retried");
        }
        if (task.snapshotJson() == null) {
            throw new IllegalStateException("task " + taskId + " has no snapshot to retry with");
        }
        // 重试必须使用原快照：仅重置状态与重试计数，snapshot_json/snapshot_digest 保持不变
        boolean reset = taskRepository.resetForRetry(taskId);
        if (!reset) {
            throw new ConflictException("task " + taskId + " was concurrently modified");
        }
        taskRepository.incrementRetryCount(taskId, task.retryCount() + 1);
        afterCommit(this::scheduleDispatch);
        return taskRepository.findById(taskId);
    }

    /**
     * 手动排空待执行队列；autoDispatch 关闭时由测试调用以精确控制任务领取时机，
     * 一旦开始会持续领取直到没有 PENDING 任务。
     */
    public void drainQueue() {
        dispatchPending();
    }

    private void scheduleDispatch() {
        if (autoDispatch) {
            dispatchPending();
        }
    }

    /**
     * 抢占一个 PENDING 任务并提交到优化线程池；工作线程结束后继续排空队列。
     * 在提交事务 afterCommit 阶段调用，因此使用独立短事务。
     */
    private void dispatchPending() {
        Long taskId = claimNextInNewTransaction();
        if (taskId == null) {
            return;
        }
        executor.execute(() -> {
            try {
                runTask(taskId);
            } finally {
                dispatchPending();
            }
        });
    }

    private Long claimNextInNewTransaction() {
        return txTemplate.execute(status -> {
            List<OptimizationTask> pending = taskRepository.findPendingForMerge();
            for (OptimizationTask candidate : pending) {
                if (taskRepository.markRunning(candidate.id(), Instant.now(clock))) {
                    return candidate.id();
                }
            }
            return null;
        });
    }

    /**
     * 执行单个任务：使用任务保存的快照（重试路径同样如此），
     * 完成时做结果版本保护：已存在基于更多有效边（更高图版本）的结果则标记 SUPERSEDED。
     */
    private void runTask(long taskId) {
        OptimizationTask task = taskRepository.findById(taskId);
        if (task == null || task.status() != TaskStatus.RUNNING) {
            return;
        }
        OptimizationInput input = snapshotBuilder.parse(task.snapshotJson(), task.snapshotDigest());
        OptimizationOutput output;
        try {
            output = optimizer.optimize(input);
        } catch (RuntimeException e) {
            log.warn("optimization task {} failed: {}", taskId, e.getMessage());
            recordFailure(taskId, e);
            return;
        }
        persistOutcome(taskId, task.targetGraphVersion(), output);
    }

    private void recordFailure(long taskId, Exception e) {
        txTemplate.executeWithoutResult(status -> {
            OptimizationTask running = taskRepository.findById(taskId);
            if (running == null || running.status() != TaskStatus.RUNNING) {
                return;
            }
            String reason = (e instanceof RetryableOptimizationException ? "Retryable" : "Unexpected")
                    + "OptimizationException: " + e.getMessage();
            taskRepository.markFailed(taskId, reason, running.retryCount(), Instant.now(clock));
        });
    }

    /**
     * 结果版本保护（独立短事务）：
     * 已有更高图版本结果 → 当前任务 SUPERSEDED，不写结果；否则写入本版本结果并置 SUCCEEDED。
     */
    void persistOutcome(long taskId, int targetGraphVersion, OptimizationOutput output) {
        txTemplate.executeWithoutResult(status -> {
            // 与图写入、任务提交共用 graph_meta 行锁，串行化结果落库：
            // 获得锁后再读最高已落库版本，避免两个并发任务都通过版本检查
            graphMetaRepository.lockAndGetCurrentVersion();
            int latestVersion = resultRepository.latestVersion();
            if (latestVersion > targetGraphVersion) {
                taskRepository.markSuperseded(taskId, Instant.now(clock));
                return;
            }
            String posesJson;
            try {
                posesJson = objectMapper.writeValueAsString(output.poses());
            } catch (JsonProcessingException e) {
                throw new IllegalStateException("failed to serialize optimized poses", e);
            }
            try {
                resultRepository.insert(new OptimizationResult(
                        null, taskId, targetGraphVersion, posesJson, output.finalCost(),
                        output.iterations(), Instant.now(clock)));
                taskRepository.markSucceeded(taskId, Instant.now(clock));
            } catch (DuplicateKeyException e) {
                // 同版本结果已由其他任务落库，旧结果不得覆盖
                taskRepository.markSuperseded(taskId, Instant.now(clock));
            }
        });
    }

    private void ensureRequestIdNotUsedByGraph(String requestId) {
        if (keyframeRepository.findByRequestId(requestId) != null) {
            throw new ConflictException("requestId '" + requestId + "' already used by a keyframe");
        }
        if (edgeRepository.findByRequestId(requestId) != null) {
            throw new ConflictException("requestId '" + requestId + "' already used by an edge");
        }
    }

    private static void afterCommit(Runnable action) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    action.run();
                }
            });
        } else {
            action.run();
        }
    }
}
