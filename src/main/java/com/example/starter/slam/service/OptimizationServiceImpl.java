package com.example.starter.slam.service;

import com.example.starter.slam.api.OptimizeRequest;
import com.example.starter.slam.api.ResultView;
import com.example.starter.slam.api.TaskView;
import com.example.starter.slam.domain.TaskStatus;
import com.example.starter.slam.po.OptimizeResultPO;
import com.example.starter.slam.po.OptimizeTaskPO;
import com.example.starter.slam.po.PoseGraphPO;
import com.example.starter.slam.po.TaskRequestPO;
import com.example.starter.slam.queue.OptimizationQueue;
import com.example.starter.slam.store.GraphStore;
import com.example.starter.slam.store.TaskStore;
import com.example.starter.slam.web.ConflictException;
import com.example.starter.slam.web.NotFoundException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 优化任务服务实现：提交（同一图未开始任务合并为覆盖最高版本的任务）、
 * requestId 幂等与 409 冲突、原快照重试与结果查询。
 */
@Service
public class OptimizationServiceImpl implements OptimizationService {

    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {
    };
    private static final TypeReference<List<List<Double>>> POSE_LIST = new TypeReference<>() {
    };

    private final GraphStore graphStore;
    private final TaskStore taskStore;
    private final OptimizationQueue queue;
    private final ObjectMapper objectMapper;
    private final int maxAttempts;

    public OptimizationServiceImpl(GraphStore graphStore, TaskStore taskStore, OptimizationQueue queue,
                                   ObjectMapper objectMapper,
                                   @Value("${slam.queue.max-attempts:3}") int maxAttempts) {
        this.graphStore = graphStore;
        this.taskStore = taskStore;
        this.queue = queue;
        this.objectMapper = objectMapper;
        this.maxAttempts = maxAttempts;
    }

    @Override
    @Transactional
    public TaskView submit(OptimizeRequest request) {
        String graphId = request.graphId();

        // 锁定图行，串行化同一图上"查找待执行任务/合并/建任务"的判定
        PoseGraphPO graph = graphStore.lockGraph(graphId);
        if (graph == null) {
            throw new NotFoundException("graph not found: " + graphId);
        }

        TaskRequestPO existingRequest = taskStore.findRequest(request.requestId());
        if (existingRequest != null) {
            if (!graphId.equals(existingRequest.graphId())) {
                throw new ConflictException("requestId " + request.requestId()
                        + " already bound to graph " + existingRequest.graphId());
            }
            String effectiveTaskId = existingRequest.mergedIntoTaskId() != null
                    ? existingRequest.mergedIntoTaskId() : existingRequest.taskId();
            OptimizeTaskPO effectiveTask = taskStore.findTask(effectiveTaskId);
            return toView(effectiveTask, existingRequest.mergedIntoTaskId());
        }

        long targetVersion = graph.currentVersion();
        Instant now = Instant.now();
        OptimizeTaskPO pending = taskStore.findMergablePendingTask(graphId);

        String taskId;
        String mergedIntoTaskId;
        if (pending != null) {
            // 合并到尚未开始的任务，目标版本提升为被合并请求中的最高版本
            taskId = pending.taskId();
            mergedIntoTaskId = taskId;
            long mergedVersion = Math.max(pending.targetVersion(), targetVersion);
            taskStore.mergeInto(request.requestId(), taskId, mergedVersion, now);
            try {
                taskStore.insertRequest(new TaskRequestPO(
                        request.requestId(), taskId, graphId, targetVersion, taskId, now));
            } catch (DuplicateKeyException ex) {
                throw new ConflictException("requestId " + request.requestId() + " already exists");
            }
        } else {
            taskId = UUID.randomUUID().toString();
            mergedIntoTaskId = null;
            OptimizeTaskPO task = new OptimizeTaskPO(taskId, graphId, targetVersion, TaskStatus.PENDING,
                    0, null, null, null, null, now, now, null, null);
            taskStore.insertTask(task);
            try {
                taskStore.insertRequest(new TaskRequestPO(
                        request.requestId(), taskId, graphId, targetVersion, null, now));
            } catch (DuplicateKeyException ex) {
                throw new ConflictException("requestId " + request.requestId() + " already exists");
            }
        }

        signalAfterCommit();
        return toView(taskStore.findTask(taskId), mergedIntoTaskId);
    }

    @Override
    public TaskView getTask(String taskId) {
        OptimizeTaskPO task = taskStore.findTask(taskId);
        if (task == null) {
            throw new NotFoundException("task not found: " + taskId);
        }
        return toView(task, null);
    }

    @Override
    public TaskView getByRequestId(String requestId) {
        TaskRequestPO request = taskStore.findRequest(requestId);
        if (request == null) {
            throw new NotFoundException("optimization request not found: " + requestId);
        }
        String effectiveTaskId = request.mergedIntoTaskId() != null
                ? request.mergedIntoTaskId() : request.taskId();
        OptimizeTaskPO task = taskStore.findTask(effectiveTaskId);
        if (task == null) {
            throw new NotFoundException("task not found: " + effectiveTaskId);
        }
        return toView(task, request.mergedIntoTaskId());
    }

    @Override
    @Transactional
    public TaskView retry(String taskId) {
        OptimizeTaskPO task = taskStore.findTask(taskId);
        if (task == null) {
            throw new NotFoundException("task not found: " + taskId);
        }
        if (task.status() != TaskStatus.FAILED_RETRYABLE) {
            throw new ConflictException("task " + taskId + " is not in FAILED_RETRYABLE state: "
                    + task.status());
        }
        if (task.attempts() >= maxAttempts) {
            throw new ConflictException("task " + taskId + " reached max attempts " + maxAttempts
                    + "; create a new task for a fresh snapshot");
        }
        if (task.snapshotJson() == null) {
            throw new IllegalStateException("retryable task " + taskId + " has no persisted snapshot");
        }
        taskStore.requeueForRetry(taskId, Instant.now());
        signalAfterCommit();
        return toView(taskStore.findTask(taskId), null);
    }

    @Override
    public ResultView getResult(String taskId) {
        OptimizeTaskPO task = taskStore.findTask(taskId);
        if (task == null) {
            throw new NotFoundException("task not found: " + taskId);
        }
        OptimizeResultPO result = taskStore.findResultByTask(taskId);
        if (result == null) {
            throw new NotFoundException("no successful result for task " + taskId
                    + " (status " + task.status() + ")");
        }
        return new ResultView(result.resultId(), result.taskId(), result.graphId(), result.graphVersion(),
                read(result.frameIdsJson(), STRING_LIST),
                read(result.posesJson(), POSE_LIST),
                result.cost(), result.iterations(), result.createdAt());
    }

    private TaskView toView(OptimizeTaskPO task, String mergedIntoTaskId) {
        List<String> requestIds = taskStore.requestIdsForTask(task.taskId());
        return new TaskView(task.taskId(), requestIds, task.graphId(), task.targetVersion(),
                task.status(), task.attempts(), task.failureReason(), task.snapshotSummary(),
                task.resultVersion(), mergedIntoTaskId, task.createdAt(), task.updatedAt());
    }

    private void signalAfterCommit() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    queue.signal();
                }
            });
        } else {
            queue.signal();
        }
    }

    private <T> T read(String json, TypeReference<T> type) {
        try {
            return objectMapper.readValue(json, type);
        } catch (Exception ex) {
            throw new IllegalStateException("failed to parse stored json", ex);
        }
    }
}
