package com.example.starter.audit;

import com.example.starter.audit.api.CompareResult;
import com.example.starter.audit.api.CreateTaskRequest;
import com.example.starter.audit.api.RecoveryResult;
import com.example.starter.audit.api.StageView;
import com.example.starter.audit.api.TaskView;
import com.example.starter.audit.domain.TaskAuditService;
import com.example.starter.audit.persistence.TaskAuditRepository;
import com.example.starter.audit.web.ApiException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 审计平台主流程集成测试：真实 H2(MODE=MySQL) 内存库、真实 JDBC 事务、
 * 真实产物文件与确定性执行器，不 mock 数据库边界。
 */
@SpringBootTest(properties = "spring.task.execution.shutdown.await-termination=false")
class AuditPlatformIntegrationTest {

    @TempDir
    static Path artifactDir;

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",
                () -> "jdbc:h2:mem:audit_it;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
        registry.add("spring.datasource.driver-class-name", () -> "org.h2.Driver");
        registry.add("spring.datasource.username", () -> "sa");
        registry.add("spring.datasource.password", () -> "");
        registry.add("audit.artifacts.dir", () -> artifactDir.toString());
    }

    @Autowired
    private TaskAuditService service;

    @Autowired
    private TaskAuditRepository repository;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void cleanTables() throws Exception {
        jdbc.update("DELETE FROM task_checkpoint");
        jdbc.update("DELETE FROM task_stage");
        jdbc.update("DELETE FROM task_run");
        if (Files.exists(artifactDir)) {
            try (var paths = Files.walk(artifactDir)) {
                paths.filter(Files::isRegularFile).forEach(p -> {
                    try {
                        Files.delete(p);
                    } catch (Exception ignored) {
                        // 临时目录清理失败不影响用例隔离
                    }
                });
            }
        }
    }

    // ---------------- 主流程与版本校验 ----------------

    @Test
    void missingRequiredVersionReturns422() {
        CreateTaskRequest request = new CreateTaskRequest(
                "req-missing", "code-v1", "  ", "map-v1", "bag-v1", 42L, null, null);
        assertThatThrownBy(() -> service.createAndRun(request))
                .isInstanceOfSatisfying(ApiException.class, ex -> {
                    assertThat(ex.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                    assertThat(ex.getMessage()).contains("configVersion");
                });
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM task_run", Long.class)).isZero();
    }

    @Test
    void happyPathExecutesAllStagesInOrderWithCheckpoints() {
        TaskView task = run(baseRequest("req-happy", 7L, null, null));

        assertThat(task.state()).isEqualTo("SUCCESS");
        assertThat(task.resultHash()).isNotBlank();
        assertThat(task.stages()).extracting(StageView::stage)
                .containsExactly("perception", "localization", "planning", "control");
        assertThat(task.stages()).allSatisfy(stage -> {
            assertThat(stage.state()).isEqualTo("SUCCESS");
            assertThat(stage.startedAt()).isNotNull();
            assertThat(stage.finishedAt()).isNotNull();
            assertThat(stage.metrics()).isNotEmpty();
            assertThat(stage.artifactPath()).endsWith(".out");
            assertThat(stage.artifactSha256()).hasSize(64);
            assertThat(stage.outputDigest()).hasSize(64);
            assertThat(stage.replayCommand()).contains("--stage " + stage.stage())
                    .contains("--seed 7");
        });
        // 严格依赖顺序：下游检查点的上游摘要必须等于上一阶段输出摘要
        List<String> outputs = task.stages().stream().map(StageView::outputDigest).toList();
        for (int i = 1; i < outputs.size(); i++) {
            Map<String, Object> cp = checkpointOf(task.id(), task.stages().get(i).stage());
            assertThat(cp.get("UPSTREAM_DIGEST")).isEqualTo(outputs.get(i - 1));
            assertThat(cp.get("OUTPUT_DIGEST")).isEqualTo(outputs.get(i));
        }
        assertThat(repository.listCheckpoints(task.id())).hasSize(4);
    }

    // ---------------- 阶段失败与中断 ----------------

    @Test
    void stageFailureMarksTaskAndStageFailedAndStopsPipeline() {
        TaskView task = run(baseRequest("req-fail", 1L, null, "fail:planning"));

        assertThat(task.state()).isEqualTo("FAILED");
        assertThat(task.errorMessage()).contains("planning");
        assertThat(task.stages()).extracting(StageView::state)
                .containsExactly("SUCCESS", "SUCCESS", "FAILED");
        assertThat(task.stages()).noneMatch(s -> "control".equals(s.stage()));
        // 失败阶段不产生检查点，成功阶段必须有
        assertThat(repository.listCheckpoints(task.id()))
                .extracting(c -> c.stage()).containsExactly("perception", "localization");
    }

    @Test
    void interruptionMarksTaskInterrupted() {
        TaskView task = run(baseRequest("req-interrupt", 1L, null,
                "interrupt:control"));

        assertThat(task.state()).isEqualTo("INTERRUPTED");
        StageView control = task.stages().stream().filter(s -> s.stage().equals("control"))
                .findFirst().orElseThrow();
        assertThat(control.state()).isEqualTo("INTERRUPTED");
        assertThat(control.errorMessage()).contains("中断");
        assertThat(repository.listCheckpoints(task.id())).hasSize(3);
    }

    // ---------------- 检查点恢复 ----------------

    @Test
    void recoverAfterFailureResumesFromLatestCheckpointAndReplaysOnlyDownstream() {
        TaskView failed = run(baseRequest("req-recover", 9L, null, "fail:planning"));
        assertThat(failed.state()).isEqualTo("FAILED");

        RecoveryResult result = service.recover(failed.id());

        assertThat(result.recovered()).isTrue();
        assertThat(result.resumedFrom()).isEqualTo("localization");
        assertThat(result.replayedStages()).containsExactly("planning", "control");
        assertThat(result.reasons()).isEmpty();
        TaskView recovered = result.task();
        assertThat(recovered.state()).isEqualTo("SUCCESS");
        assertThat(recovered.resultHash()).isNotBlank();
        // 成功阶段保留首批记录，重跑阶段批次递增，未跳过任何阶段
        assertThat(recovered.stages()).extracting(StageView::attempt)
                .containsExactly(1, 1, 2, 2);
    }

    @Test
    void recoverAfterInterruptionRerunsInterruptedStage() {
        TaskView interrupted = run(
                baseRequest("req-recover-int", 3L, null, "interrupt:control"));

        RecoveryResult result = service.recover(interrupted.id());

        assertThat(result.recovered()).isTrue();
        assertThat(result.resumedFrom()).isEqualTo("planning");
        assertThat(result.replayedStages()).containsExactly("control");
        assertThat(result.task().state()).isEqualTo("SUCCESS");
    }

    @Test
    void changedDependencyDigestBlocksRecoveryWithReason() {
        TaskView failed = run(
                baseRequest("req-drift-digest", 5L, null, "fail:control"));
        // 删除中间检查点，制造"跳过 localization"的断裂链
        jdbc.update("DELETE FROM task_checkpoint WHERE task_id = ? AND stage = 'localization'",
                failed.id());

        RecoveryResult result = service.recover(failed.id());

        assertThat(result.recovered()).isFalse();
        assertThat(result.reasons()).anyMatch(r -> r.contains("阶段顺序断裂"));
        assertThat(service.getTask(failed.id()).state()).isEqualTo("FAILED");
    }

    @Test
    void changedBindingBlocksRecoveryWithReason() {
        TaskView failed = run(
                baseRequest("req-drift-binding", 5L, null, "fail:planning"));
        jdbc.update("UPDATE task_run SET code_version = 'tampered-code' WHERE id = ?", failed.id());

        RecoveryResult result = service.recover(failed.id());

        assertThat(result.recovered()).isFalse();
        assertThat(result.reasons()).anyMatch(r -> r.contains("绑定版本"));
    }

    @Test
    void corruptedArtifactBlocksRecoveryWithSha256Reason() throws Exception {
        TaskView failed = run(
                baseRequest("req-drift-artifact", 5L, null, "fail:planning"));
        StageView perception = failed.stages().get(0);
        Path artifact = artifactDir.resolve(perception.artifactPath());
        Files.writeString(artifact, "tampered artifact content");

        RecoveryResult result = service.recover(failed.id());

        assertThat(result.recovered()).isFalse();
        assertThat(result.reasons()).anyMatch(r -> r.contains("SHA-256"));
    }

    @Test
    void recoveringSuccessfulTaskReturns409() {
        TaskView task = run(baseRequest("req-recover-ok", 1L, null, null));
        assertThatThrownBy(() -> service.recover(task.id()))
                .isInstanceOfSatisfying(ApiException.class,
                        ex -> assertThat(ex.getStatus()).isEqualTo(HttpStatus.CONFLICT));
    }

    // ---------------- 比较 ----------------

    @Test
    void comparableTasksReportBindingAndNumericMetricDiffs() {
        TaskView left = run(baseRequest("req-cmp-l", 11L, null, null));
        TaskView right = run(baseRequest("req-cmp-r", 22L, null, null));

        CompareResult result = service.compare(left.id(), right.id());

        assertThat(result.comparable()).isTrue();
        assertThat(result.incomparableItems()).isEmpty();
        assertThat(result.bindingDiffs().get("seed").changed()).isTrue();
        assertThat(result.bindingDiffs().get("codeVersion").changed()).isFalse();
        assertThat(result.metricDiffs().keySet())
                .containsExactly("perception", "localization", "planning", "control");
        // 数值差必须等于右减左，不能以零代替
        for (StageView l : left.stages()) {
            StageView r = stageOf(right, l.stage());
            result.metricDiffs().get(l.stage()).forEach((metric, diff) ->
                    assertThat(diff).isEqualTo(round4(r.metrics().get(metric)
                            - l.metrics().get(metric))));
        }
    }

    @Test
    void differentMetricCaliberIsMarkedIncomparableWithoutZeroFill() {
        TaskView left = run(baseRequest("req-cal-l", 11L, "default", null));
        TaskView right = run(baseRequest("req-cal-r", 11L, "extended", null));

        CompareResult result = service.compare(left.id(), right.id());

        assertThat(result.comparable()).isFalse();
        assertThat(result.incomparableItems())
                .anyMatch(r -> r.contains("指标口径不一致") && r.contains("perception"));
        // 口径不一致的阶段不得输出指标差值（禁止用零填充）
        assertThat(result.metricDiffs()).doesNotContainKey("perception");
    }

    @Test
    void failedTaskIsIncomparable() {
        TaskView left = run(baseRequest("req-fcmp-l", 1L, null, null));
        TaskView right = run(baseRequest("req-fcmp-r", 1L, null, "fail:planning"));

        CompareResult result = service.compare(left.id(), right.id());

        assertThat(result.comparable()).isFalse();
        assertThat(result.incomparableItems())
                .anyMatch(r -> r.contains("planning") && r.contains("未成功"));
    }

    // ---------------- 幂等与确定性重放 ----------------

    @Test
    void sameRequestIdAndParamsReplaysOriginalResult() {
        CreateTaskRequest first = baseRequest("req-idem", 8L, null, null);
        TaskView created = run(first);
        TaskView replayed = run(first);

        assertThat(replayed.id()).isEqualTo(created.id());
        assertThat(replayed.state()).isEqualTo("SUCCESS");
        assertThat(replayed.resultHash()).isEqualTo(created.resultHash());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM task_run WHERE request_id = 'req-idem'",
                Long.class)).isEqualTo(1L);
    }

    @Test
    void sameRequestIdDifferentParamsReturns409() {
        run(baseRequest("req-conflict", 8L, null, null));
        CreateTaskRequest changed = new CreateTaskRequest("req-conflict",
                "code-v1", "config-v2", "map-v1", "bag-v1", 8L, null, null);

        assertThatThrownBy(() -> service.createAndRun(changed))
                .isInstanceOfSatisfying(ApiException.class, ex -> {
                    assertThat(ex.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(ex.getMessage()).contains("req-conflict");
                });
    }

    @Test
    void concurrentSameRequestIdCreatesExactlyOneTask() throws Exception {
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        ConcurrentLinkedQueue<Long> ids = new ConcurrentLinkedQueue<>();
        List<Throwable> errors = new ArrayList<>();

        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    ids.add(run(
                            baseRequest("req-concurrent", 13L, null, null)).id());
                } catch (Throwable t) {
                    synchronized (errors) {
                        errors.add(t);
                    }
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertThat(done.await(30, TimeUnit.SECONDS)).isTrue();
        pool.shutdown();

        assertThat(errors).isEmpty();
        // 8 个并发同键同参请求必须幂等返回同一个任务
        assertThat(ids).hasSize(threads);
        assertThat(ids.stream().distinct()).hasSize(1);
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM task_run WHERE request_id = 'req-concurrent'", Long.class);
        assertThat(count).isEqualTo(1L);
    }

    @Test
    void sameBindingsAndSeedProduceIdenticalStageDigestsAndResultHash() {
        TaskView first = run(baseRequest("req-det-1", 99L, null, null));
        TaskView second = run(baseRequest("req-det-2", 99L, null, null));

        for (StageView stageA : first.stages()) {
            StageView stageB = stageOf(second, stageA.stage());
            assertThat(stageB.outputDigest()).isEqualTo(stageA.outputDigest());
            assertThat(stageB.artifactSha256()).isEqualTo(stageA.artifactSha256());
            assertThat(stageB.metrics()).isEqualTo(stageA.metrics());
            assertThat(stageB.replayCommand()).isEqualTo(stageA.replayCommand());
        }
        assertThat(second.resultHash()).isEqualTo(first.resultHash());

        // 不同种子必须导致不同结果，证明种子确实参与确定性计算
        TaskView otherSeed = run(baseRequest("req-det-3", 100L, null, null));
        assertThat(otherSeed.resultHash()).isNotEqualTo(first.resultHash());
    }

    @Test
    void missingRequestIdReturns422() {
        CreateTaskRequest request = new CreateTaskRequest(
                null, "code-v1", "config-v1", "map-v1", "bag-v1", 42L, null, null);
        assertThatThrownBy(() -> service.createAndRun(request))
                .isInstanceOfSatisfying(ApiException.class, ex ->
                        assertThat(ex.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY));
    }

    @Test
    void recoveredResultHashEqualsCleanRunWithSameBindingsAndSeed() {
        TaskView failed = run(baseRequest("req-det-recover-1", 17L, null, "fail:planning"));
        RecoveryResult recovered = service.recover(failed.id());
        TaskView clean = run(baseRequest("req-det-recover-2", 17L, null, null));

        assertThat(recovered.task().resultHash()).isEqualTo(clean.resultHash());
        for (StageView stage : recovered.task().stages()) {
            assertThat(stageOf(clean, stage.stage()).outputDigest())
                    .isEqualTo(stage.outputDigest());
        }
    }

    // ---------------- 辅助方法 ----------------

    private static CreateTaskRequest baseRequest(String requestId, long seed,
                                                 String profile, String faultPlan) {
        return new CreateTaskRequest(requestId, "code-v1", "config-v1", "map-v1",
                "rosbag-v1", seed, profile, faultPlan);
    }

    private TaskView run(CreateTaskRequest request) {
        return service.createAndRun(request).task();
    }

    private static StageView stageOf(TaskView task, String stage) {
        return task.stages().stream().filter(s -> s.stage().equals(stage)).findFirst().orElseThrow();
    }

    private static double round4(double value) {
        return Math.round(value * 10000.0d) / 10000.0d;
    }

    private Map<String, Object> checkpointOf(long taskId, String stage) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT * FROM task_checkpoint WHERE task_id = ? AND stage = ?", taskId, stage);
        return rows.get(0);
    }
}
