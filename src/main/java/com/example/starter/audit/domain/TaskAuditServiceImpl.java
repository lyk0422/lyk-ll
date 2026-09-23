package com.example.starter.audit.domain;

import com.example.starter.audit.api.CompareResult;
import com.example.starter.audit.api.CreateResult;
import com.example.starter.audit.api.CreateTaskRequest;
import com.example.starter.audit.api.RecoveryResult;
import com.example.starter.audit.api.StageView;
import com.example.starter.audit.api.TaskView;
import com.example.starter.audit.engine.ArtifactStore;
import com.example.starter.audit.engine.DigestSupport;
import com.example.starter.audit.model.Bindings;
import com.example.starter.audit.model.Stage;
import com.example.starter.audit.model.StageState;
import com.example.starter.audit.model.TaskState;
import com.example.starter.audit.persistence.CheckpointRow;
import com.example.starter.audit.persistence.StageRow;
import com.example.starter.audit.persistence.TaskAuditRepository;
import com.example.starter.audit.persistence.TaskRow;
import com.example.starter.audit.web.ApiException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

/**
 * 任务执行审计编排实现：
 * 严格按 感知-&gt;定位-&gt;规划-&gt;控制 顺序同步执行；阶段成功与检查点原子提交，
 * 失败/中断立即终止并固化状态；恢复前重新核对全部绑定版本、检查点链与依赖产物；
 * 比较时仅对同名同口径指标计算差值；执行结果仅由绑定、种子、上游摘要决定。
 */
@Service
public class TaskAuditServiceImpl implements TaskAuditService {

    /** 首阶段的固定根上游摘要，表示不存在上游产物。 */
    static final String ROOT_UPSTREAM_DIGEST =
            "0000000000000000000000000000000000000000000000000000000000000000";

    private static final Set<String> ALLOWED_PROFILES = Set.of("default", "extended");
    private static final TypeReference<Map<String, Double>> METRICS_TYPE = new TypeReference<>() {
    };

    private final TaskAuditRepository repository;
    private final StageExecutor stageExecutor;
    private final ArtifactStore artifactStore;
    private final DigestSupport digestSupport;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final TransactionTemplate transactions;

    public TaskAuditServiceImpl(TaskAuditRepository repository,
                                StageExecutor stageExecutor,
                                ArtifactStore artifactStore,
                                DigestSupport digestSupport,
                                ObjectMapper objectMapper,
                                Clock auditClock,
                                org.springframework.transaction.PlatformTransactionManager txManager) {
        this.repository = repository;
        this.stageExecutor = stageExecutor;
        this.artifactStore = artifactStore;
        this.digestSupport = digestSupport;
        this.objectMapper = objectMapper;
        this.clock = auditClock;
        this.transactions = new TransactionTemplate(txManager);
    }

    @Override
    public CreateResult createAndRun(CreateTaskRequest request) {
        ValidatedCreate validated = validateAndNormalize(request);

        if (validated.requestId() != null) {
            Optional<TaskRow> existing =
                    repository.findTaskByRequestId(validated.requestId());
            if (existing.isPresent()) {
                return replayIfSameParams(existing.get(), validated);
            }
        }

        long taskId;
        try {
            taskId = repository.insertTask(validated.requestId(), validated.requestHash(),
                    new TaskAuditRepository.TaskBindingsSnapshot(
                            validated.bindings().codeVersion(),
                            validated.bindings().configVersion(),
                            validated.bindings().mapVersion(),
                            validated.bindings().rosbagVersion(),
                            validated.bindings().seed(),
                            validated.metricsProfile(),
                            validated.faultPlan()));
        } catch (DuplicateKeyException e) {
            // 并发同 requestId：唯一约束兜底，再次按同键同参/异参规则处理
            TaskRow row = repository.findTaskByRequestId(validated.requestId()).orElseThrow();
            return replayIfSameParams(row, validated);
        }
        runPipeline(taskId, Stage.PERCEPTION, 1, ROOT_UPSTREAM_DIGEST);
        return new CreateResult(getTask(taskId), false);
    }

    @Override
    public TaskView getTask(long taskId) {
        TaskRow row = repository.findTaskById(taskId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "任务不存在: " + taskId));
        return toView(row);
    }

    @Override
    public RecoveryResult recover(long taskId) {
        TaskRow task = repository.findTaskById(taskId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "任务不存在: " + taskId));
        TaskState state = TaskState.valueOf(task.state());
        if (state != TaskState.FAILED && state != TaskState.INTERRUPTED) {
            throw new ApiException(HttpStatus.CONFLICT,
                    "仅失败或已中断任务可以恢复，当前状态: " + state);
        }

        List<String> reasons = new ArrayList<>();
        List<CheckpointRow> checkpoints = repository.listCheckpoints(taskId);
        List<CheckpointRow> verified = verifyCheckpointChain(task, checkpoints, reasons);
        if (!reasons.isEmpty()) {
            return new RecoveryResult(false, null, List.of(), toView(task), List.copyOf(reasons));
        }

        String upstreamDigest = ROOT_UPSTREAM_DIGEST;
        Stage startStage = Stage.PERCEPTION;
        String resumedFrom = null;
        if (!verified.isEmpty()) {
            CheckpointRow last = verified.get(verified.size() - 1);
            resumedFrom = last.stage();
            upstreamDigest = last.outputDigest();
            startStage = Stage.fromCode(last.stage()).ordinal() == Stage.values().length - 1
                    ? null : Stage.values()[Stage.fromCode(last.stage()).ordinal() + 1];
        }
        if (startStage == null) {
            // 理论不可达：末阶段成功时任务应为 SUCCESS
            reasons.add("所有阶段均已有成功检查点，任务无需恢复");
            return new RecoveryResult(false, resumedFrom, List.of(), toView(task), reasons);
        }

        int nextAttempt = repository.listStages(taskId).stream()
                .mapToInt(StageRow::attempt).max().orElse(0) + 1;
        PipelineOutcome outcome = runPipeline(taskId, startStage, nextAttempt, upstreamDigest);

        List<String> replayed = new ArrayList<>();
        for (Stage stage : Stage.values()) {
            if (stage.ordinal() >= startStage.ordinal()) {
                replayed.add(stage.getCode());
            }
        }
        TaskView view = getTask(taskId);
        if (outcome == PipelineOutcome.SUCCESS) {
            return new RecoveryResult(true, resumedFrom, replayed, view, List.of());
        }
        reasons.add("恢复执行在阶段 " + outcome.stage().getCode() + " 再次"
                + (outcome.interrupted ? "中断" : "失败")
                + "：" + outcome.message());
        return new RecoveryResult(false, resumedFrom, replayed, view, reasons);
    }

    @Override
    public CompareResult compare(long leftTaskId, long rightTaskId) {
        TaskRow left = repository.findTaskById(leftTaskId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "任务不存在: " + leftTaskId));
        TaskRow right = repository.findTaskById(rightTaskId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "任务不存在: " + rightTaskId));

        Map<String, CompareResult.BindingDiff> bindingDiffs = new LinkedHashMap<>();
        bindingDiffs.put("codeVersion",
                diff(left.codeVersion(), right.codeVersion()));
        bindingDiffs.put("configVersion",
                diff(left.configVersion(), right.configVersion()));
        bindingDiffs.put("mapVersion",
                diff(left.mapVersion(), right.mapVersion()));
        bindingDiffs.put("rosbagVersion",
                diff(left.rosbagVersion(), right.rosbagVersion()));
        bindingDiffs.put("seed",
                diff(String.valueOf(left.seed()), String.valueOf(right.seed())));

        List<String> incomparable = new ArrayList<>();
        Map<String, StageRow> rightStages = collectLatestStages(right, incomparable, "右侧");
        Map<String, StageRow> leftStages = collectLatestStages(left, incomparable, "左侧");
        compareStageSets(leftStages.keySet(), rightStages.keySet(), incomparable);

        Map<String, Map<String, Double>> metricDiffs = new LinkedHashMap<>();
        for (Stage stage : Stage.values()) {
            StageRow l = leftStages.get(stage.getCode());
            StageRow r = rightStages.get(stage.getCode());
            if (l == null || r == null) {
                continue;
            }
            Map<String, Double> lm = parseMetrics(l);
            Map<String, Double> rm = parseMetrics(r);
            if (!lm.keySet().equals(rm.keySet())) {
                incomparable.add("阶段 " + stage.getCode() + " 指标口径不一致：左侧独有 "
                        + minus(lm.keySet(), rm.keySet()) + "，右侧独有 "
                        + minus(rm.keySet(), lm.keySet()));
                continue;
            }
            Map<String, Double> diffs = new TreeMap<>();
            for (String metric : lm.keySet()) {
                diffs.put(metric, round(lm.get(metric), rm.get(metric)));
            }
            metricDiffs.put(stage.getCode(), diffs);
        }

        return new CompareResult(incomparable.isEmpty(), bindingDiffs, metricDiffs, incomparable);
    }

    // ------------------------------------------------------------------
    // 执行编排
    // ------------------------------------------------------------------

    /**
     * 从指定阶段开始顺序执行到末阶段或首个失败/中断点。
     * 每个阶段的成功状态与检查点在同一事务内提交；执行本身在事务外完成。
     */
    PipelineOutcome runPipeline(long taskId, Stage startStage, int attempt, String initialUpstream) {
        TaskRow task = repository.findTaskById(taskId).orElseThrow();
        Bindings bindings = bindingsOf(task);
        repository.markTaskRunning(taskId, Instant.now(clock));

        String upstreamDigest = initialUpstream;
        for (Stage stage : Stage.values()) {
            if (stage.ordinal() < startStage.ordinal()) {
                continue;
            }
            long stageId = transactions.execute(status -> {
                long id = repository.insertStage(taskId, stage, attempt);
                repository.markStageRunning(id, Instant.now(clock));
                return id;
            });

            StageContext context = new StageContext(taskId, stage, bindings, upstreamDigest,
                    attempt, task.metricsProfile(), task.faultPlan());
            StageProduct product;
            try {
                product = stageExecutor.execute(context);
            } catch (StageFailureException e) {
                finishWithFailure(taskId, stageId, StageState.FAILED, TaskState.FAILED, e.getMessage());
                return PipelineOutcome.failed(stage, e.getMessage());
            } catch (StageInterruptedException e) {
                finishWithFailure(taskId, stageId, StageState.INTERRUPTED, TaskState.INTERRUPTED,
                        e.getMessage());
                return PipelineOutcome.interrupted(stage, e.getMessage());
            } catch (RuntimeException e) {
                finishWithFailure(taskId, stageId, StageState.FAILED, TaskState.FAILED,
                        "阶段执行异常: " + e);
                return PipelineOutcome.failed(stage, String.valueOf(e));
            }

            Map<String, Double> sortedMetrics = new TreeMap<>(product.metrics());
            String metricsJson = digestSupport.canonicalJson(sortedMetrics);
            ArtifactStore.StoredArtifact stored =
                    artifactStore.store(taskId, attempt, stage.getCode(), product.artifactBytes());
            String outputDigest = digestSupport.sha256Hex(digestSupport.canonicalJson(mapOf(
                    "stage", stage.getCode(),
                    "upstreamDigest", upstreamDigest,
                    "artifactSha256", stored.sha256(),
                    "metrics", metricsJson,
                    "replayCommand", product.replayCommand())));

            final String newUpstream = outputDigest;
            final String stageUpstream = upstreamDigest;
            transactions.executeWithoutResult(status -> {
                repository.markStageSuccess(stageId, new TaskAuditRepository.StageSuccessData(
                        metricsJson, stored.relativePath(), stored.sha256(),
                        newUpstream, product.replayCommand()), Instant.now(clock));
                repository.upsertCheckpoint(new CheckpointRow(
                        null, taskId, stage.getCode(), attempt,
                        bindings.codeVersion(), bindings.configVersion(),
                        bindings.mapVersion(), bindings.rosbagVersion(), bindings.seed(),
                        stageUpstream, newUpstream, stored.sha256(), metricsJson,
                        Instant.now(clock)));
            });
            upstreamDigest = outputDigest;
        }

        String resultHash = digestSupport.sha256Hex("RESULT:" + upstreamDigest);
        repository.markTaskFinished(taskId, TaskState.SUCCESS.name(), resultHash, null,
                Instant.now(clock));
        return PipelineOutcome.SUCCESS;
    }

    private void finishWithFailure(long taskId, long stageId, StageState stageState,
                                   TaskState taskState, String message) {
        Instant at = Instant.now(clock);
        transactions.executeWithoutResult(status -> {
            repository.markStageTerminal(stageId, stageState, message, at);
            repository.markTaskFinished(taskId, taskState.name(), null, message, at);
        });
    }

    // ------------------------------------------------------------------
    // 恢复核对
    // ------------------------------------------------------------------

    /**
     * 重新核对全部绑定版本、检查点链、阶段记录与依赖产物。
     * 任一不一致都收集明确原因；全部通过时返回按阶段顺序排列的检查点前缀链。
     */
    private List<CheckpointRow> verifyCheckpointChain(TaskRow task, List<CheckpointRow> checkpoints,
                                                      List<String> reasons) {
        long taskId = task.id();
        String expectedUpstream = ROOT_UPSTREAM_DIGEST;
        List<CheckpointRow> verified = new ArrayList<>();

        for (int i = 0; i < checkpoints.size(); i++) {
            CheckpointRow cp = checkpoints.get(i);
            Stage expectedStage = Stage.values()[i];
            String label = "检查点[" + cp.stage() + "]";

            if (!cp.stage().equals(expectedStage.getCode())) {
                reasons.add(label + " 阶段顺序断裂：期望 " + expectedStage.getCode()
                        + "，实际 " + cp.stage() + "，存在跳过阶段");
                return List.of();
            }
            if (!equals(cp.codeVersion(), task.codeVersion())
                    || !equals(cp.configVersion(), task.configVersion())
                    || !equals(cp.mapVersion(), task.mapVersion())
                    || !equals(cp.rosbagVersion(), task.rosbagVersion())
                    || cp.seed() != task.seed()) {
                reasons.add(label + " 绑定版本与任务当前绑定不一致（代码/配置/地图/rosbag/种子至少一项变化）");
            }
            if (!expectedUpstream.equals(cp.upstreamDigest())) {
                reasons.add(label + " 上游产物摘要不匹配，依赖链已断裂");
            }
            StageRow stageRow = repository.findLatestStage(taskId, expectedStage).orElse(null);
            if (stageRow == null || !StageState.SUCCESS.name().equals(stageRow.state())
                    || stageRow.attempt() != cp.attempt()) {
                reasons.add(label + " 缺少与检查点一致的成功阶段记录");
            } else {
                if (!equals(stageRow.artifactSha256(), cp.artifactSha256())
                        || !equals(stageRow.outputDigest(), cp.outputDigest())
                        || !equals(stageRow.metricsJson(), cp.metricsJson())) {
                    reasons.add(label + " 阶段记录的产物摘要/输出摘要/指标与检查点不一致");
                }
                if (!artifactStore.verify(stageRow.artifactPath(), cp.artifactSha256())) {
                    reasons.add(label + " 依赖产物缺失或 SHA-256 校验失败: "
                            + stageRow.artifactPath());
                }
            }
            verified.add(cp);
            expectedUpstream = cp.outputDigest();
        }
        return reasons.isEmpty() ? verified : List.of();
    }

    // ------------------------------------------------------------------
    // 视图与辅助
    // ------------------------------------------------------------------

    private TaskView toView(TaskRow row) {
        List<StageView> stages = repository.listLatestStages(row.id()).stream()
                .map(this::toStageView).toList();
        return new TaskView(row.id(), row.requestId(), row.state(),
                row.codeVersion(), row.configVersion(), row.mapVersion(), row.rosbagVersion(),
                row.seed(), row.resultHash(), row.errorMessage(),
                row.createdAt(), row.startedAt(), row.finishedAt(), stages);
    }

    private StageView toStageView(StageRow row) {
        return new StageView(row.stage(), row.attempt(), row.state(),
                row.startedAt(), row.finishedAt(), parseMetrics(row), row.errorMessage(),
                row.artifactPath(), row.artifactSha256(), row.outputDigest(),
                row.replayCommand());
    }

    private Map<String, Double> parseMetrics(StageRow row) {
        if (row.metricsJson() == null || row.metricsJson().isBlank()) {
            return Map.of();
        }
        try {
            return new TreeMap<>(objectMapper.readValue(row.metricsJson(), METRICS_TYPE));
        } catch (Exception e) {
            throw new IllegalStateException("阶段指标 JSON 损坏: " + row.stage(), e);
        }
    }

    private CreateResult replayIfSameParams(TaskRow existing, ValidatedCreate validated) {
        if (!existing.requestHash().equals(validated.requestHash())) {
            throw new ApiException(HttpStatus.CONFLICT,
                    "requestId=" + validated.requestId() + " 已绑定不同参数，拒绝同键异参重放");
        }
        return new CreateResult(toView(existing), true);
    }

    private ValidatedCreate validateAndNormalize(CreateTaskRequest request) {
        List<String> missing = new ArrayList<>();
        if (isBlank(request.requestId())) {
            missing.add("requestId");
        }
        if (isBlank(request.codeVersion())) {
            missing.add("codeVersion");
        }
        if (isBlank(request.configVersion())) {
            missing.add("configVersion");
        }
        if (isBlank(request.mapVersion())) {
            missing.add("mapVersion");
        }
        if (isBlank(request.rosbagVersion())) {
            missing.add("rosbagVersion");
        }
        if (request.seed() == null) {
            missing.add("seed");
        }
        if (!missing.isEmpty()) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "必填绑定版本缺失: " + String.join(", ", missing));
        }
        String profile = isBlank(request.metricsProfile())
                ? "default" : request.metricsProfile().trim().toLowerCase();
        if (!ALLOWED_PROFILES.contains(profile)) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "metricsProfile 仅支持 default/extended，实际: " + request.metricsProfile());
        }
        String faultPlan = normalizeFaultPlan(request.faultPlan());
        Bindings bindings = new Bindings(request.codeVersion().trim(),
                request.configVersion().trim(), request.mapVersion().trim(),
                request.rosbagVersion().trim(), request.seed());
        String requestHash = digestSupport.sha256Hex(digestSupport.canonicalJson(mapOf(
                "codeVersion", bindings.codeVersion(),
                "configVersion", bindings.configVersion(),
                "mapVersion", bindings.mapVersion(),
                "rosbagVersion", bindings.rosbagVersion(),
                "seed", bindings.seed(),
                "metricsProfile", profile,
                "faultPlan", faultPlan == null ? "" : faultPlan)));
        String requestId = request.requestId().trim();
        return new ValidatedCreate(requestId, requestHash, bindings, profile, faultPlan);
    }

    private String normalizeFaultPlan(String raw) {
        if (isBlank(raw)) {
            return null;
        }
        String plan = raw.trim();
        String[] parts = plan.split(":", 2);
        if (parts.length != 2
                || (!"fail".equals(parts[0]) && !"interrupt".equals(parts[0]))) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "faultPlan 格式必须为 fail:<阶段> 或 interrupt:<阶段>，实际: " + raw);
        }
        try {
            Stage.fromCode(parts[1].trim());
        } catch (IllegalArgumentException e) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "faultPlan 指定了未知阶段: " + parts[1]);
        }
        return parts[0] + ":" + parts[1].trim();
    }

    private void compareStageSets(Set<String> left, Set<String> right, List<String> items) {
        for (Stage stage : Stage.values()) {
            String code = stage.getCode();
            if (left.contains(code) && !right.contains(code)) {
                items.add("阶段集合不一致：右侧缺少阶段 " + code);
            }
            if (!left.contains(code) && right.contains(code)) {
                items.add("阶段集合不一致：左侧缺少阶段 " + code);
            }
        }
    }

    private Map<String, StageRow> collectLatestStages(TaskRow task, List<String> items,
                                                      String side) {
        Map<String, StageRow> result = new LinkedHashMap<>();
        for (StageRow row : repository.listLatestStages(task.id())) {
            result.put(row.stage(), row);
            if (!StageState.SUCCESS.name().equals(row.state())) {
                items.add(side + "任务[" + task.id() + "]阶段 " + row.stage()
                        + " 未成功（状态 " + row.state() + "），无指标可比较");
            }
        }
        return result;
    }

    private static Bindings bindingsOf(TaskRow row) {
        return new Bindings(row.codeVersion(), row.configVersion(), row.mapVersion(),
                row.rosbagVersion(), row.seed());
    }

    private static CompareResult.BindingDiff diff(String left, String right) {
        return new CompareResult.BindingDiff(left, right, !equals(left, right));
    }

    private static double round(Double left, Double right) {
        return Math.round((right - left) * 10000.0d) / 10000.0d;
    }

    private static Set<String> minus(Set<String> a, Set<String> b) {
        Set<String> result = new java.util.TreeSet<>(a);
        result.removeAll(b);
        return result;
    }

    private static boolean equals(String a, String b) {
        return a == null ? b == null : a.equals(b);
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    /** 构造保序键值对，键固定为 String、值可混合，供规范化 JSON 使用。 */
    private static Map<String, Object> mapOf(Object... kv) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            map.put((String) kv[i], kv[i + 1]);
        }
        return map;
    }

    /** 校验并规范化后的创建参数。 */
    private record ValidatedCreate(String requestId, String requestHash, Bindings bindings,
                                   String metricsProfile, String faultPlan) {
    }

    /** 单次管线执行结果。 */
    private static final class PipelineOutcome {

        static final PipelineOutcome SUCCESS = new PipelineOutcome(null, true, false, null);

        private final Stage stage;
        private final boolean success;
        private final boolean interrupted;
        private final String message;

        private PipelineOutcome(Stage stage, boolean success, boolean interrupted, String message) {
            this.stage = stage;
            this.success = success;
            this.interrupted = interrupted;
            this.message = message;
        }

        static PipelineOutcome failed(Stage stage, String message) {
            return new PipelineOutcome(stage, false, false, message);
        }

        static PipelineOutcome interrupted(Stage stage, String message) {
            return new PipelineOutcome(stage, false, true, message);
        }

        Stage stage() {
            return stage;
        }

        String message() {
            return message;
        }
    }
}
