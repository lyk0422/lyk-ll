package com.example.starter.audit;

import com.example.starter.audit.api.CompareResponse;
import com.example.starter.audit.api.CreateTaskRequest;
import com.example.starter.audit.api.RecoverResponse;
import com.example.starter.audit.api.StageResponse;
import com.example.starter.audit.api.TaskResponse;
import com.example.starter.audit.engine.DeterministicStageExecutor;
import com.example.starter.audit.engine.DigestUtil;
import com.example.starter.audit.engine.StageExecutionException;
import com.example.starter.audit.engine.StageExecutionResult;
import com.example.starter.audit.engine.StageExecutor;
import com.example.starter.audit.engine.ArtifactStore;
import com.example.starter.audit.persist.AuditRepository;
import com.example.starter.audit.persist.CheckpointPO;
import com.example.starter.audit.persist.JsonUtil;
import com.example.starter.audit.persist.RequestPO;
import com.example.starter.audit.persist.StagePO;
import com.example.starter.audit.persist.TaskPO;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * 任务执行审计编排服务。
 *
 * <p>职责：创建任务并按依赖顺序执行阶段、逐阶段提交检查点；失败/中断后从最近成功检查点
 * 恢复（恢复前重新核对绑定版本、检查点链与依赖产物摘要）；requestId 幂等重放；两次任务
 * 绑定与指标差异比较。阶段结果仅由绑定版本、种子与上游摘要链决定，保证确定性重放。</p>
 */
@Service
public class TaskAuditService {

    private final AuditRepository repository;
    private final StageExecutor stageExecutor;
    private final ArtifactStore artifactStore;
    private final Clock clock;
    private final TransactionTemplate transactionTemplate;

    public TaskAuditService(AuditRepository repository,
                            StageExecutor stageExecutor,
                            ArtifactStore artifactStore,
                            Clock clock,
                            TransactionTemplate transactionTemplate) {
        this.repository = repository;
        this.stageExecutor = stageExecutor;
        this.artifactStore = artifactStore;
        this.clock = clock;
        this.transactionTemplate = transactionTemplate;
    }

    // ==================== 创建与幂等重放 ====================

    public TaskResponse createOrReplay(CreateTaskRequest request) {
        normalize(request);
        String requestHash = canonicalRequestHash(request);

        if (request.getRequestId() != null) {
            RequestPO existing = repository.findRequest(request.getRequestId());
            if (existing != null) {
                if (!existing.getRequestHash().equals(requestHash)) {
                    throw new AuditApiException(409,
                            "requestId=" + request.getRequestId() + " 已绑定不同参数，拒绝同键异参请求");
                }
                // 等待原请求对应任务进入终态，重放完整原结果
                TaskPO winnerTask = awaitTerminal(existing.getTaskId());
                TaskResponse replay = toResponse(winnerTask,
                        repository.findStagesByTaskId(existing.getTaskId()));
                replay.setReplayed(true);
                return replay;
            }
        }

        long now = nowMillis();
        TaskPO task = newTaskPO(request, now);
        try {
            // 占位幂等记录与任务/阶段行在同一事务内创建；并发同键时唯一约束触发重复键异常
            Long taskId = transactionTemplate.execute(status -> {
                if (request.getRequestId() != null) {
                    RequestPO placeholder = new RequestPO();
                    placeholder.setRequestId(request.getRequestId());
                    placeholder.setTaskId(0L);
                    placeholder.setRequestHash(requestHash);
                    placeholder.setRequestJson(canonicalRequestJson(request));
                    placeholder.setCreatedAt(now);
                    repository.insertRequest(placeholder);
                }
                long id = repository.insertTask(task);
                for (Stage stage : Stage.ordered()) {
                    StagePO stagePO = new StagePO();
                    stagePO.setTaskId(id);
                    stagePO.setStage(stage.name());
                    stagePO.setSeqNo(stage.getSeqNo());
                    stagePO.setStatus(StageStatus.PENDING.name());
                    stagePO.setAttempt(0);
                    repository.insertStage(stagePO);
                }
                if (request.getRequestId() != null) {
                    repository.bindRequestTask(request.getRequestId(), id);
                }
                return id;
            });

            repository.markTaskRunning(taskId, now);
            return runFromStage(taskId, 0);
        } catch (DuplicateKeyException e) {
            // 并发同键：唯一约束（requestId）保证只有一方创建成功，另一方重放赢家结果
            if (request.getRequestId() == null) {
                throw e;
            }
            RequestPO winner = repository.findRequest(request.getRequestId());
            if (winner != null && winner.getRequestHash().equals(requestHash)) {
                // 等待赢方执行进入终态，确保重放的是完整原结果而非执行中间态
                TaskPO winnerTask = awaitTerminal(winner.getTaskId());
                TaskResponse replay = toResponse(winnerTask,
                        repository.findStagesByTaskId(winner.getTaskId()));
                replay.setReplayed(true);
                return replay;
            }
            throw new AuditApiException(409,
                    "requestId=" + request.getRequestId() + " 已绑定不同参数，拒绝同键异参请求");
        }
    }

    // ==================== 阶段执行编排 ====================

    /**
     * 从指定序号阶段开始执行；序号之前的阶段必须已有成功检查点。
     * 每个阶段独立提交，失败或中断后之前的检查点保留。
     */
    private TaskResponse runFromStage(long taskId, int fromSeq) {
        TaskPO task = repository.findTaskById(taskId);
        List<StagePO> stages = repository.findStagesByTaskId(taskId);
        Map<String, StagePO> stageByName = stages.stream()
                .collect(Collectors.toMap(StagePO::getStage, s -> s, (a, b) -> a, LinkedHashMap::new));

        for (Stage stage : Stage.ordered()) {
            if (stage.getSeqNo() < fromSeq) {
                continue;
            }
            StagePO stagePO = stageByName.get(stage.name());
            if (StageStatus.SUCCESS.name().equals(stagePO.getStatus())) {
                continue;
            }

            Map<String, String> upstreamDigests = collectUpstreamDigests(stages, stage.getSeqNo());
            int attempt = (stagePO.getAttempt() == null ? 0 : stagePO.getAttempt()) + 1;
            long startedAt = nowMillis();
            repository.markStageRunning(taskId, stage.name(), attempt, startedAt);

            StageExecutor.StageExecutionContext context = new StageExecutor.StageExecutionContext(
                    taskId, stage, task.getSeed(), attempt,
                    task.getCodeVersion(), task.getConfigVersion(), task.getMapVersion(),
                    task.getRosbagVersion(), upstreamDigests,
                    task.getFailStage(), task.getFailTimes() == null ? 0 : task.getFailTimes());

            StageExecutionResult result;
            try {
                result = stageExecutor.execute(context);
            } catch (StageExecutionException ex) {
                long failedAt = nowMillis();
                repository.markStageFailed(taskId, stage.name(), failedAt, ex.getMessage());
                repository.updateTaskExecution(taskId, TaskStatus.FAILED.name(), null,
                        "阶段 " + stage.name() + " 执行失败: " + ex.getMessage(),
                        failedAt, failedAt);
                return loadResponse(taskId);
            }

            // 产物落盘并以实际字节回算摘要，杜绝记录摘要与产物不一致
            artifactStore.write(result.artifactPath(), result.artifactContent());
            String actualDigest = DigestUtil.sha256(result.artifactContent());
            if (!actualDigest.equals(result.artifactSha256())) {
                throw new IllegalStateException("阶段产物摘要自检不一致: " + stage.name());
            }

            long finishedAt = nowMillis();
            String metricsJson = JsonUtil.writeMap(result.metrics());

            StagePO success = new StagePO();
            success.setTaskId(taskId);
            success.setStage(stage.name());
            success.setFinishedAt(finishedAt);
            success.setMetricsJson(metricsJson);
            success.setArtifactPath(result.artifactPath());
            success.setArtifactSha256(actualDigest);
            success.setReplayCommand(result.replayCommand());

            CheckpointPO checkpoint = new CheckpointPO();
            checkpoint.setTaskId(taskId);
            checkpoint.setStage(stage.name());
            checkpoint.setCheckpointSeq(stage.getSeqNo());
            checkpoint.setCodeVersion(task.getCodeVersion());
            checkpoint.setConfigVersion(task.getConfigVersion());
            checkpoint.setMapVersion(task.getMapVersion());
            checkpoint.setRosbagVersion(task.getRosbagVersion());
            checkpoint.setSeed(task.getSeed());
            checkpoint.setUpstreamDigestsJson(JsonUtil.writeMap(upstreamDigests));
            checkpoint.setStageOutputDigest(actualDigest);
            checkpoint.setArtifactPath(result.artifactPath());
            checkpoint.setArtifactSha256(actualDigest);
            checkpoint.setMetricsJson(metricsJson);
            checkpoint.setReplayCommand(result.replayCommand());
            checkpoint.setCreatedAt(finishedAt);
            repository.completeStageWithCheckpoint(success, checkpoint);
            stages = repository.findStagesByTaskId(taskId);

            // 中断注入：该阶段检查点已落库，任务进入 INTERRUPTED，后续阶段标记 INTERRUPTED
            if (stage.name().equals(task.getInterruptAfter())) {
                repository.markRemainingStagesInterrupted(taskId, stage.name());
                repository.updateTaskExecution(taskId, TaskStatus.INTERRUPTED.name(), null,
                        "阶段 " + stage.name() + " 检查点完成后任务被中断（模拟注入）",
                        finishedAt, finishedAt);
                return loadResponse(taskId);
            }
        }

        // 全部阶段成功：计算规范化阶段摘要结果哈希
        List<StagePO> finalStages = repository.findStagesByTaskId(taskId);
        String resultHash = computeResultHash(task, finalStages);
        long finishedAt = nowMillis();
        repository.updateTaskExecution(taskId, TaskStatus.SUCCESS.name(), resultHash,
                null, finishedAt, finishedAt);
        return loadResponse(taskId);
    }

    /** 收集序号之前全部成功阶段的产物摘要，作为下游确定性输入。 */
    private Map<String, String> collectUpstreamDigests(List<StagePO> stages, int beforeSeq) {
        Map<String, String> digests = new LinkedHashMap<>();
        stages.stream()
                .filter(s -> s.getSeqNo() < beforeSeq)
                .sorted(Comparator.comparingInt(StagePO::getSeqNo))
                .forEach(s -> {
                    if (!StageStatus.SUCCESS.name().equals(s.getStatus()) || s.getArtifactSha256() == null) {
                        throw new AuditApiException(422,
                                "上游阶段 " + s.getStage() + " 无成功产物，禁止跳过阶段执行");
                    }
                    digests.put(s.getStage(), s.getArtifactSha256());
                });
        return digests;
    }

    // ==================== 检查点恢复 ====================

    public RecoverResponse recover(long taskId) {
        TaskPO task = repository.findTaskById(taskId);
        if (task == null) {
            throw new AuditApiException(404, "任务不存在: " + taskId);
        }
        RecoverResponse response = new RecoverResponse();
        response.setTaskId(taskId);

        if (TaskStatus.SUCCESS.name().equals(task.getStatus())) {
            response.setStatus("ALREADY_SUCCESS");
            response.setReason("任务已成功，无需恢复");
            response.setTask(loadResponse(taskId));
            return response;
        }
        if (TaskStatus.RUNNING.name().equals(task.getStatus())
                || TaskStatus.PENDING.name().equals(task.getStatus())) {
            throw new AuditApiException(422,
                    "任务当前状态为 " + task.getStatus() + "，仅失败或已中断任务可恢复");
        }

        List<CheckpointPO> checkpoints = repository.findCheckpoints(taskId);
        List<StagePO> stages = repository.findStagesByTaskId(taskId);
        Map<String, StagePO> stageByName = stages.stream()
                .collect(Collectors.toMap(StagePO::getStage, s -> s, (a, b) -> a, LinkedHashMap::new));

        // 恢复前重新核对全部绑定版本、检查点连续性与依赖产物
        String reason = validateCheckpointChain(task, checkpoints, stageByName);
        if (reason != null) {
            response.setStatus("UNRECOVERABLE");
            response.setReason(reason);
            response.setTask(loadResponse(taskId));
            return response;
        }

        int fromSeq = checkpoints.isEmpty() ? 0 : checkpoints.get(checkpoints.size() - 1).getCheckpointSeq() + 1;
        if (fromSeq >= Stage.ordered().length) {
            response.setStatus("ALREADY_SUCCESS");
            response.setReason("全部阶段检查点均已存在，无需恢复");
            response.setTask(loadResponse(taskId));
            return response;
        }

        // 核对通过：任务置运行中并从最近成功检查点之后继续，不跳过任何阶段
        long now = nowMillis();
        repository.markTaskRunning(taskId, now);
        TaskResponse updated = runFromStage(taskId, fromSeq);
        response.setStatus("RECOVERED");
        response.setReason(null);
        response.setTask(updated);
        return response;
    }

    /**
     * 核对检查点链：
     * 1) 每个检查点绑定版本（代码/配置/地图/输入/种子）与任务一致；
     * 2) 检查点序号从 0 连续且与阶段记录的成功状态一致；
     * 3) 上游产物摘要链与前序检查点输出一致；
     * 4) 依赖产物文件存在且回读 SHA-256 与检查点、阶段记录一致。
     *
     * @return 不可恢复原因；null 表示核对通过
     */
    private String validateCheckpointChain(TaskPO task, List<CheckpointPO> checkpoints,
                                           Map<String, StagePO> stageByName) {
        // 成功阶段集合必须与检查点集合一一对应：不允许成功阶段缺少检查点（否则可能跳过核对）
        long successCount = stageByName.values().stream()
                .filter(s -> StageStatus.SUCCESS.name().equals(s.getStatus())).count();
        if (successCount != checkpoints.size()) {
            return "成功阶段数(" + successCount + ")与检查点数(" + checkpoints.size()
                    + ")不一致，检查点链不完整，拒绝恢复";
        }

        Map<String, CheckpointPO> cpByStage = new LinkedHashMap<>();
        int expectedSeq = 0;
        for (CheckpointPO cp : checkpoints) {
            String label = "检查点[" + cp.getStage() + "] ";
            if (cp.getCheckpointSeq() != expectedSeq) {
                return label + "序号不连续，期望 " + expectedSeq + " 实际 " + cp.getCheckpointSeq();
            }
            String expectedStage = Stage.ordered()[expectedSeq].name();
            if (!expectedStage.equals(cp.getStage())) {
                return label + "阶段名与序号不匹配，期望 " + expectedStage;
            }
            String bindingMismatch = checkBinding(task, cp, label);
            if (bindingMismatch != null) {
                return bindingMismatch;
            }
            StagePO stagePO = stageByName.get(cp.getStage());
            if (stagePO == null || !StageStatus.SUCCESS.name().equals(stagePO.getStatus())) {
                return label + "对应阶段记录不是成功状态";
            }
            if (!cp.getStageOutputDigest().equals(stagePO.getArtifactSha256())) {
                return label + "阶段输出摘要与阶段记录产物摘要不一致";
            }

            Map<String, Object> upstreamRaw = JsonUtil.readMap(cp.getUpstreamDigestsJson());
            Map<String, String> upstream = toStringMap(upstreamRaw);
            Set<String> expectedUpstream = new LinkedHashSet<>();
            for (Stage prior : Stage.ordered()) {
                if (prior.getSeqNo() < expectedSeq) {
                    expectedUpstream.add(prior.name());
                }
            }
            if (!upstream.keySet().equals(expectedUpstream)) {
                return label + "上游依赖集合不一致，期望 " + expectedUpstream + " 实际 " + upstream.keySet();
            }
            for (String priorStage : expectedUpstream) {
                String expectedDigest = cpByStage.get(priorStage).getStageOutputDigest();
                if (!expectedDigest.equals(upstream.get(priorStage))) {
                    return label + "上游产物摘要变化：" + priorStage + " 期望 " + expectedDigest
                            + " 实际 " + upstream.get(priorStage);
                }
            }

            // 回读产物文件核对实际 SHA-256
            if (cp.getArtifactPath() == null) {
                return label + "缺少产物相对路径";
            }
            byte[] content = artifactStore.read(cp.getArtifactPath());
            if (content == null) {
                return label + "依赖产物文件丢失: " + cp.getArtifactPath();
            }
            String actualDigest = DigestUtil.sha256(content);
            if (!actualDigest.equals(cp.getStageOutputDigest())
                    || !actualDigest.equals(cp.getArtifactSha256())
                    || !actualDigest.equals(stagePO.getArtifactSha256())) {
                return label + "依赖产物摘要与检查点记录不一致，实际 " + actualDigest;
            }

            cpByStage.put(cp.getStage(), cp);
            expectedSeq++;
        }
        return null;
    }

    private String checkBinding(TaskPO task, CheckpointPO cp, String label) {
        if (!cp.getCodeVersion().equals(task.getCodeVersion())) {
            return label + "代码版本不一致: 任务=" + task.getCodeVersion() + " 检查点=" + cp.getCodeVersion();
        }
        if (!cp.getConfigVersion().equals(task.getConfigVersion())) {
            return label + "配置版本不一致: 任务=" + task.getConfigVersion() + " 检查点=" + cp.getConfigVersion();
        }
        if (!cp.getMapVersion().equals(task.getMapVersion())) {
            return label + "地图版本不一致: 任务=" + task.getMapVersion() + " 检查点=" + cp.getMapVersion();
        }
        if (!cp.getRosbagVersion().equals(task.getRosbagVersion())) {
            return label + "输入rosbag版本不一致: 任务=" + task.getRosbagVersion() + " 检查点=" + cp.getRosbagVersion();
        }
        if (!cp.getSeed().equals(task.getSeed())) {
            return label + "随机种子不一致: 任务=" + task.getSeed() + " 检查点=" + cp.getSeed();
        }
        return null;
    }

    // ==================== 任务比较 ====================

    public CompareResponse compare(Long leftId, Long rightId) {
        TaskPO left = repository.findTaskById(leftId);
        TaskPO right = repository.findTaskById(rightId);
        if (left == null) {
            throw new AuditApiException(404, "任务不存在: " + leftId);
        }
        if (right == null) {
            throw new AuditApiException(404, "任务不存在: " + rightId);
        }

        CompareResponse response = new CompareResponse();
        response.setLeftTaskId(leftId);
        response.setRightTaskId(rightId);

        Map<String, CompareResponse.BindingDiff> bindingDiffs = new LinkedHashMap<>();
        bindingDiffs.put("codeVersion",
                new CompareResponse.BindingDiff(left.getCodeVersion(), right.getCodeVersion(),
                        !left.getCodeVersion().equals(right.getCodeVersion())));
        bindingDiffs.put("configVersion",
                new CompareResponse.BindingDiff(left.getConfigVersion(), right.getConfigVersion(),
                        !left.getConfigVersion().equals(right.getConfigVersion())));
        bindingDiffs.put("mapVersion",
                new CompareResponse.BindingDiff(left.getMapVersion(), right.getMapVersion(),
                        !left.getMapVersion().equals(right.getMapVersion())));
        bindingDiffs.put("rosbagVersion",
                new CompareResponse.BindingDiff(left.getRosbagVersion(), right.getRosbagVersion(),
                        !left.getRosbagVersion().equals(right.getRosbagVersion())));
        bindingDiffs.put("seed",
                new CompareResponse.BindingDiff(String.valueOf(left.getSeed()),
                        String.valueOf(right.getSeed()),
                        !left.getSeed().equals(right.getSeed())));
        response.setBindingDiffs(bindingDiffs);

        Map<String, Map<String, Double>> leftMetrics = metricsByStage(repository.findStagesByTaskId(leftId));
        Map<String, Map<String, Double>> rightMetrics = metricsByStage(repository.findStagesByTaskId(rightId));

        List<String> incomparableStages = new ArrayList<>();
        List<CompareResponse.MetricIncomparable> incomparableMetrics = new ArrayList<>();
        Map<String, Map<String, Double>> metricDiffs = new LinkedHashMap<>();

        Set<String> allStages = new LinkedHashSet<>();
        allStages.addAll(leftMetrics.keySet());
        allStages.addAll(rightMetrics.keySet());
        for (String stage : allStages) {
            Map<String, Double> lm = leftMetrics.get(stage);
            Map<String, Double> rm = rightMetrics.get(stage);
            if (lm == null || rm == null) {
                // 标记该阶段仅存在于哪一侧
                incomparableStages.add(stage + "(" + (lm == null ? "RIGHT_ONLY" : "LEFT_ONLY") + ")");
                continue;
            }
            Set<String> names = new LinkedHashSet<>();
            names.addAll(lm.keySet());
            names.addAll(rm.keySet());
            Map<String, Double> diffs = new LinkedHashMap<>();
            for (String metric : names) {
                Double lv = lm.get(metric);
                Double rv = rm.get(metric);
                if (lv == null || rv == null) {
                    incomparableMetrics.add(new CompareResponse.MetricIncomparable(stage, metric,
                            lv == null ? "RIGHT_ONLY" : "LEFT_ONLY"));
                    continue;
                }
                diffs.put(metric, round6(rv - lv));
            }
            metricDiffs.put(stage, diffs);
        }

        response.setIncomparableStages(incomparableStages);
        response.setMetricDiffs(metricDiffs);
        response.setIncomparableMetrics(incomparableMetrics);
        response.setComparable(incomparableStages.isEmpty() && incomparableMetrics.isEmpty());
        return response;
    }

    // ==================== 查询与映射 ====================

    public TaskResponse getTask(Long taskId) {
        TaskPO task = repository.findTaskById(taskId);
        if (task == null) {
            throw new AuditApiException(404, "任务不存在: " + taskId);
        }
        return toResponse(task, repository.findStagesByTaskId(taskId));
    }

    private TaskResponse loadResponse(long taskId) {
        return toResponse(repository.findTaskById(taskId), repository.findStagesByTaskId(taskId));
    }

    private TaskResponse toResponse(TaskPO po, List<StagePO> stages) {
        TaskResponse response = new TaskResponse();
        response.setId(po.getId());
        response.setTaskCode(po.getTaskCode());
        response.setRequestId(po.getRequestId());
        response.setStatus(po.getStatus());
        response.setCodeVersion(po.getCodeVersion());
        response.setConfigVersion(po.getConfigVersion());
        response.setMapVersion(po.getMapVersion());
        response.setRosbagVersion(po.getRosbagVersion());
        response.setSeed(po.getSeed());
        response.setFailStage(po.getFailStage());
        response.setFailTimes(po.getFailTimes());
        response.setInterruptAfter(po.getInterruptAfter());
        response.setResultHash(po.getResultHash());
        response.setErrorMessage(po.getErrorMessage());
        response.setStartedAt(po.getStartedAt());
        response.setFinishedAt(po.getFinishedAt());
        response.setCreatedAt(po.getCreatedAt());
        response.setUpdatedAt(po.getUpdatedAt());
        response.setStages(stages.stream().map(this::toStageResponse).toList());
        response.setReplayed(false);
        return response;
    }

    private StageResponse toStageResponse(StagePO po) {
        StageResponse response = new StageResponse();
        response.setStage(po.getStage());
        response.setSeqNo(po.getSeqNo());
        response.setStatus(po.getStatus());
        response.setAttempt(po.getAttempt());
        response.setStartedAt(po.getStartedAt());
        response.setFinishedAt(po.getFinishedAt());
        Map<String, Object> raw = JsonUtil.readMap(po.getMetricsJson());
        Map<String, Double> metrics = new LinkedHashMap<>();
        raw.forEach((k, v) -> metrics.put(k, v instanceof Number n ? n.doubleValue() : null));
        response.setMetrics(metrics);
        response.setErrorMessage(po.getErrorMessage());
        response.setArtifactPath(po.getArtifactPath());
        response.setArtifactSha256(po.getArtifactSha256());
        response.setReplayCommand(po.getReplayCommand());
        return response;
    }

    // ==================== 辅助方法 ====================

    private TaskPO newTaskPO(CreateTaskRequest request, long now) {
        TaskPO po = new TaskPO();
        po.setTaskCode(request.getTaskCode());
        po.setRequestId(request.getRequestId());
        po.setStatus(TaskStatus.PENDING.name());
        po.setCodeVersion(request.getCodeVersion());
        po.setConfigVersion(request.getConfigVersion());
        po.setMapVersion(request.getMapVersion());
        po.setRosbagVersion(request.getRosbagVersion());
        po.setSeed(request.getSeed());
        po.setFailStage(request.getFailStage());
        po.setFailTimes(request.getFailTimes() == null ? 0 : request.getFailTimes());
        po.setInterruptAfter(request.getInterruptAfter());
        po.setStartedAt(now);
        po.setCreatedAt(now);
        po.setUpdatedAt(now);
        return po;
    }

    private void normalize(CreateTaskRequest request) {
        request.setTaskCode(trim(request.getTaskCode()));
        request.setRequestId(trim(request.getRequestId()));
        request.setCodeVersion(trim(request.getCodeVersion()));
        request.setConfigVersion(trim(request.getConfigVersion()));
        request.setMapVersion(trim(request.getMapVersion()));
        request.setRosbagVersion(trim(request.getRosbagVersion()));
        request.setFailStage(trim(request.getFailStage()));
        request.setInterruptAfter(trim(request.getInterruptAfter()));
        if (request.getFailStage() == null) {
            request.setFailTimes(0);
        }
    }

    private String trim(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /** 规范化创建参数：固定键序 JSON，不含 requestId，用于幂等判定。 */
    private String canonicalRequestJson(CreateTaskRequest request) {
        Map<String, Object> canonical = new TreeMap<>();
        canonical.put("taskCode", request.getTaskCode());
        canonical.put("codeVersion", request.getCodeVersion());
        canonical.put("configVersion", request.getConfigVersion());
        canonical.put("mapVersion", request.getMapVersion());
        canonical.put("rosbagVersion", request.getRosbagVersion());
        canonical.put("seed", request.getSeed());
        canonical.put("failStage", request.getFailStage());
        canonical.put("failTimes", request.getFailTimes());
        canonical.put("interruptAfter", request.getInterruptAfter());
        return JsonUtil.writeMap(canonical);
    }

    private String canonicalRequestHash(CreateTaskRequest request) {
        return DigestUtil.sha256(canonicalRequestJson(request));
    }

    /**
     * 结果哈希：绑定版本与种子 + 各阶段规范化指标与产物摘要。
     * 相同绑定与种子的重放必须产生相同哈希。
     */
    private String computeResultHash(TaskPO task, List<StagePO> stages) {
        StringBuilder sb = new StringBuilder();
        sb.append("code=").append(task.getCodeVersion()).append('\n');
        sb.append("config=").append(task.getConfigVersion()).append('\n');
        sb.append("map=").append(task.getMapVersion()).append('\n');
        sb.append("rosbag=").append(task.getRosbagVersion()).append('\n');
        sb.append("seed=").append(task.getSeed()).append('\n');
        stages.stream().sorted(Comparator.comparingInt(StagePO::getSeqNo)).forEach(po -> {
            sb.append("[stage]").append(po.getStage()).append('\n');
            Map<String, Object> raw = JsonUtil.readMap(po.getMetricsJson());
            TreeMap<String, Object> sorted = new TreeMap<>(raw);
            sorted.forEach((k, v) -> sb.append(k).append('=').append(v).append('\n'));
            sb.append("artifact=").append(po.getArtifactSha256()).append('\n');
        });
        return DigestUtil.sha256(sb.toString());
    }

    private Map<String, Map<String, Double>> metricsByStage(List<StagePO> stages) {
        Map<String, Map<String, Double>> result = new LinkedHashMap<>();
        stages.stream().sorted(Comparator.comparingInt(StagePO::getSeqNo)).forEach(po -> {
            Map<String, Object> raw = JsonUtil.readMap(po.getMetricsJson());
            Map<String, Double> metrics = new LinkedHashMap<>();
            raw.forEach((k, v) -> metrics.put(k, v instanceof Number n ? n.doubleValue() : null));
            result.put(po.getStage(), metrics);
        });
        return result;
    }

    @SuppressWarnings("unchecked")
    private Map<String, String> toStringMap(Map<String, Object> raw) {
        Map<String, String> result = new LinkedHashMap<>();
        raw.forEach((k, v) -> result.put(k, v == null ? null : String.valueOf(v)));
        return result;
    }

    private double round6(double value) {
        return Math.round(value * 1_000_000.0d) / 1_000_000.0d;
    }

    private long nowMillis() {
        return java.time.Instant.now(clock).toEpochMilli();
    }

    /** 等待并发赢方任务进入终态（成功/失败/中断），最多 30 秒。 */
    private TaskPO awaitTerminal(long taskId) {
        long deadline = System.currentTimeMillis() + 30_000L;
        TaskPO task;
        while (true) {
            task = repository.findTaskById(taskId);
            if (task == null) {
                throw new AuditApiException(500, "幂等请求对应的任务不存在: " + taskId);
            }
            String status = task.getStatus();
            if (TaskStatus.SUCCESS.name().equals(status)
                    || TaskStatus.FAILED.name().equals(status)
                    || TaskStatus.INTERRUPTED.name().equals(status)) {
                return task;
            }
            if (System.currentTimeMillis() >= deadline) {
                throw new AuditApiException(500, "等待原任务执行完成超时: " + taskId);
            }
            try {
                Thread.sleep(20L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AuditApiException(500, "等待原任务执行完成被中断");
            }
        }
    }
}
