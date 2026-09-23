package com.example.starter.audit;

import com.example.starter.audit.api.CompareResponse;
import com.example.starter.audit.api.CreateTaskRequest;
import com.example.starter.audit.api.RecoverResponse;
import com.example.starter.audit.api.StageResponse;
import com.example.starter.audit.api.TaskResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 审计平台 H2(MODE=MySQL) 集成测试：真实数据库约束、事务提交边界、唯一约束并发裁决、
 * 真实产物文件 SHA-256 核对，不使用 mock 或 Map 代替数据库边界。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class TaskAuditServiceH2Test {

    private static final Path ARTIFACT_ROOT;

    static {
        try {
            ARTIFACT_ROOT = Files.createTempDirectory("audit-artifacts-test");
        } catch (Exception e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registry.add("audit.artifact-root", () -> ARTIFACT_ROOT.toString());
    }

    @Autowired
    private TaskAuditService service;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private MockMvc mockMvc;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void cleanDatabase() {
        // 按外键依赖顺序清理，保证场景间无顺序依赖
        jdbcTemplate.update("DELETE FROM request_record");
        jdbcTemplate.update("DELETE FROM stage_checkpoint");
        jdbcTemplate.update("DELETE FROM task_stage");
        jdbcTemplate.update("DELETE FROM audit_task");
    }

    private CreateTaskRequest baseRequest() {
        CreateTaskRequest request = new CreateTaskRequest();
        request.setTaskCode("lane-follow-demo");
        request.setCodeVersion("code@a1b2c3d4");
        request.setConfigVersion("config@e5f6a7b8");
        request.setMapVersion("map@sh-campus-v3");
        request.setRosbagVersion("rosbag@sha256:9f86d081");
        request.setSeed(20260923L);
        return request;
    }

    // ---------------- 主流程 ----------------

    @Test
    @DisplayName("主流程：四阶段按依赖顺序成功，记录状态/时间/指标/产物摘要/重放命令与检查点")
    void allStagesSuccess_persistAuditRecordsAndCheckpoints() throws Exception {
        TaskResponse task = service.createOrReplay(baseRequest());

        assertEquals(TaskStatus.SUCCESS.name(), task.getStatus());
        assertNotNull(task.getResultHash());
        assertEquals(64, task.getResultHash().length());
        List<StageResponse> stages = task.getStages();
        assertEquals(4, stages.size());
        for (int i = 0; i < stages.size(); i++) {
            StageResponse stage = stages.get(i);
            assertEquals(i, stage.getSeqNo());
            assertEquals(Stage.ordered()[i].name(), stage.getStage());
            assertEquals(StageStatus.SUCCESS.name(), stage.getStatus());
            assertNotNull(stage.getStartedAt());
            assertNotNull(stage.getFinishedAt());
            assertTrue(stage.getFinishedAt() >= stage.getStartedAt());
            assertEquals(3, stage.getMetrics().size());
            assertNotNull(stage.getArtifactPath());
            assertNotNull(stage.getArtifactSha256());
            assertEquals(64, stage.getArtifactSha256().length());
            assertNotNull(stage.getReplayCommand());
            assertTrue(stage.getReplayCommand().contains("--seed 20260923"));
            // 产物文件真实存在且回读摘要与记录一致
            byte[] content = Files.readAllBytes(ARTIFACT_ROOT.resolve(stage.getArtifactPath()));
            assertEquals(stage.getArtifactSha256(),
                    com.example.starter.audit.engine.DigestUtil.sha256(content));
        }

        Integer cpCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM stage_checkpoint WHERE task_id = ?", Integer.class, task.getId());
        assertEquals(4, cpCount);
        // 检查点保存绑定版本
        Map<String, Object> cp = jdbcTemplate.queryForMap(
                "SELECT * FROM stage_checkpoint WHERE task_id = ? AND stage = 'PLANNING'", task.getId());
        assertEquals("code@a1b2c3d4", cp.get("code_version"));
        assertEquals("config@e5f6a7b8", cp.get("config_version"));
        assertEquals("map@sh-campus-v3", cp.get("map_version"));
        assertEquals("rosbag@sha256:9f86d081", cp.get("rosbag_version"));
        assertEquals(20260923L, ((Number) cp.get("seed")).longValue());
        // 规划检查点包含感知与定位两个上游产物摘要
        String upstream = (String) cp.get("upstream_digests_json");
        assertTrue(upstream.contains("PERCEPTION"));
        assertTrue(upstream.contains("LOCALIZATION"));
        assertFalse(upstream.contains("CONTROL"));
    }

    // ---------------- 版本缺失 422 ----------------

    @Test
    @DisplayName("失败分支：任一必填绑定版本缺失返回 422 并指出字段")
    void missingVersions_return422() throws Exception {
        CreateTaskRequest request = baseRequest();
        request.setRosbagVersion("  ");
        request.setSeed(null);

        mockMvc.perform(post("/api/tasks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.status").value(422))
                .andExpect(jsonPath("$.fields.rosbagVersion").exists())
                .andExpect(jsonPath("$.fields.seed").exists());

        assertEquals(0, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM audit_task", Integer.class));
    }

    // ---------------- 阶段失败 + 恢复 ----------------

    @Test
    @DisplayName("失败分支：规划阶段注入失败，任务FAILED且前序检查点保留，恢复后成功")
    void stageFailure_thenRecoverFromLatestCheckpoint() {
        CreateTaskRequest request = baseRequest();
        request.setFailStage("PLANNING");
        request.setFailTimes(1);

        TaskResponse failed = service.createOrReplay(request);
        assertEquals(TaskStatus.FAILED.name(), failed.getStatus());
        assertNull(failed.getResultHash());
        Map<String, StageResponse> byName = byStage(failed);
        assertEquals(StageStatus.SUCCESS.name(), byName.get("PERCEPTION").getStatus());
        assertEquals(StageStatus.SUCCESS.name(), byName.get("LOCALIZATION").getStatus());
        assertEquals(StageStatus.FAILED.name(), byName.get("PLANNING").getStatus());
        assertEquals(StageStatus.PENDING.name(), byName.get("CONTROL").getStatus());
        assertNotNull(byName.get("PLANNING").getErrorMessage());
        assertEquals(1, byName.get("PLANNING").getAttempt());
        // 前序两个检查点已独立事务提交
        assertEquals(2, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM stage_checkpoint WHERE task_id = ?", Integer.class, failed.getId()));

        RecoverResponse recover = service.recover(failed.getId());
        assertEquals("RECOVERED", recover.getStatus());
        TaskResponse recovered = recover.getTask();
        assertEquals(TaskStatus.SUCCESS.name(), recovered.getStatus());
        assertNotNull(recovered.getResultHash());
        Map<String, StageResponse> recoveredStages = byStage(recovered);
        assertEquals(StageStatus.SUCCESS.name(), recoveredStages.get("PLANNING").getStatus());
        assertEquals(StageStatus.SUCCESS.name(), recoveredStages.get("CONTROL").getStatus());
        // 规划阶段为重跑：attempt 递增；感知/定位未重跑
        assertEquals(2, recoveredStages.get("PLANNING").getAttempt());
        assertEquals(1, recoveredStages.get("PERCEPTION").getAttempt());
        assertEquals(4, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM stage_checkpoint WHERE task_id = ?", Integer.class, failed.getId()));
        // 前序检查点未被重建
        assertEquals(1, recoveredStages.get("PERCEPTION").getAttempt());
    }

    // ---------------- 中断 + 恢复 ----------------

    @Test
    @DisplayName("中断边界：定位检查点后中断，任务INTERRUPTED，恢复后从规划继续并成功")
    void interruptedAfterCheckpoint_recoverContinues() {
        CreateTaskRequest request = baseRequest();
        request.setInterruptAfter("LOCALIZATION");

        TaskResponse interrupted = service.createOrReplay(request);
        assertEquals(TaskStatus.INTERRUPTED.name(), interrupted.getStatus());
        assertNotNull(interrupted.getErrorMessage());
        Map<String, StageResponse> byName = byStage(interrupted);
        assertEquals(StageStatus.SUCCESS.name(), byName.get("LOCALIZATION").getStatus());
        assertEquals(StageStatus.INTERRUPTED.name(), byName.get("PLANNING").getStatus());
        assertEquals(StageStatus.INTERRUPTED.name(), byName.get("CONTROL").getStatus());
        assertEquals(2, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM stage_checkpoint WHERE task_id = ?", Integer.class, interrupted.getId()));

        RecoverResponse recover = service.recover(interrupted.getId());
        assertEquals("RECOVERED", recover.getStatus());
        TaskResponse recovered = recover.getTask();
        assertEquals(TaskStatus.SUCCESS.name(), recovered.getStatus());
        byName = byStage(recovered);
        assertEquals(StageStatus.SUCCESS.name(), byName.get("PLANNING").getStatus());
        assertEquals(StageStatus.SUCCESS.name(), byName.get("CONTROL").getStatus());
    }

    // ---------------- 依赖摘要变化阻止恢复 ----------------

    @Test
    @DisplayName("恢复核对：检查点绑定版本被篡改时返回不可恢复原因且不执行任何阶段")
    void bindingMismatch_blocksRecovery() {
        TaskResponse failed = failedAtPlanning();

        // 直接篡改检查点保存的代码版本，模拟绑定不一致
        jdbcTemplate.update(
                "UPDATE stage_checkpoint SET code_version = 'code@tampered' WHERE task_id = ? AND stage = 'PERCEPTION'",
                failed.getId());

        RecoverResponse recover = service.recover(failed.getId());
        assertEquals("UNRECOVERABLE", recover.getStatus());
        assertNotNull(recover.getReason());
        assertTrue(recover.getReason().contains("代码版本不一致"), "原因应指明绑定版本不一致: " + recover.getReason());
        // 任务仍为失败，规划阶段未被重跑
        TaskResponse after = service.getTask(failed.getId());
        assertEquals(TaskStatus.FAILED.name(), after.getStatus());
        assertEquals(1, byStage(after).get("PLANNING").getAttempt());
    }

    @Test
    @DisplayName("恢复核对：上游产物摘要变化阻止恢复并标记具体阶段")
    void upstreamDigestChanged_blocksRecovery() {
        TaskResponse failed = failedAtPlanning();

        // 篡改定位检查点记录的感知上游摘要，模拟依赖产物摘要变化
        String tamperedUpstream = "{\"PERCEPTION\":\"" + "a".repeat(64) + "\"}";
        jdbcTemplate.update(
                "UPDATE stage_checkpoint SET upstream_digests_json = ? WHERE task_id = ? AND stage = 'LOCALIZATION'",
                tamperedUpstream, failed.getId());

        RecoverResponse recover = service.recover(failed.getId());
        assertEquals("UNRECOVERABLE", recover.getStatus());
        assertTrue(recover.getReason().contains("上游产物摘要变化"),
                "原因应指明上游摘要变化: " + recover.getReason());
        assertTrue(recover.getReason().contains("PERCEPTION"));
    }

    @Test
    @DisplayName("恢复核对：依赖产物文件丢失或内容变化时回读SHA-256不一致，阻止恢复")
    void artifactFileTampered_blocksRecovery() throws Exception {
        TaskResponse failed = failedAtPlanning();

        Path perceptionArtifact = ARTIFACT_ROOT.resolve(
                byStage(failed).get("PERCEPTION").getArtifactPath());
        Files.writeString(perceptionArtifact, "tampered artifact content");

        RecoverResponse recover = service.recover(failed.getId());
        assertEquals("UNRECOVERABLE", recover.getStatus());
        assertTrue(recover.getReason().contains("依赖产物摘要"),
                "原因应指明产物摘要不一致: " + recover.getReason());
    }

    @Test
    @DisplayName("恢复核对：检查点被删除导致链不完整时阻止恢复，且HTTP层返回422")
    void missingCheckpoint_blocksRecoveryWith422() throws Exception {
        TaskResponse failed = failedAtPlanning();

        jdbcTemplate.update(
                "DELETE FROM stage_checkpoint WHERE task_id = ? AND stage = 'LOCALIZATION'",
                failed.getId());

        RecoverResponse recover = service.recover(failed.getId());
        assertEquals("UNRECOVERABLE", recover.getStatus());
        assertTrue(recover.getReason().contains("检查点链不完整"),
                "原因应指明检查点链不完整: " + recover.getReason());

        mockMvc.perform(post("/api/tasks/{id}/recover", failed.getId()))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.status").value("UNRECOVERABLE"))
                .andExpect(jsonPath("$.reason").exists());
    }

    // ---------------- 幂等：重复请求与同键异参 ----------------

    @Test
    @DisplayName("幂等：requestId 同键同参重放原结果（200），同键异参返回 409")
    void idempotentReplay_andConflictOnDifferentParams() throws Exception {
        CreateTaskRequest first = baseRequest();
        first.setRequestId("req-001");
        TaskResponse created = service.createOrReplay(first);
        assertFalse(created.getReplayed());

        // 同键同参：返回同一任务并标记重放
        TaskResponse replay = service.createOrReplay(first);
        assertTrue(replay.getReplayed());
        assertEquals(created.getId(), replay.getId());
        assertEquals(1, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM audit_task", Integer.class));

        // HTTP 层面：同键同参 200
        mockMvc.perform(post("/api/tasks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(first)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.replayed").value(true))
                .andExpect(jsonPath("$.id").value(created.getId().intValue()));

        // 同键异参（改种子）：409
        CreateTaskRequest conflict = baseRequest();
        conflict.setRequestId("req-001");
        conflict.setSeed(999L);
        mockMvc.perform(post("/api/tasks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(conflict)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409));
        assertEquals(1, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM audit_task", Integer.class));
    }

    @Test
    @DisplayName("并发边界：并发相同requestId同参只创建一个任务，另一方重放同一结果")
    void concurrentSameRequestId_singleTaskWins() throws Exception {
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        Set<Long> taskIds = ConcurrentHashMap.newKeySet();
        Set<String> statuses = ConcurrentHashMap.newKeySet();
        List<Future<?>> futures = new ArrayList<>();

        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                ready.countDown();
                start.await();
                CreateTaskRequest request = baseRequest();
                request.setRequestId("req-concurrent-1");
                TaskResponse response = service.createOrReplay(request);
                taskIds.add(response.getId());
                statuses.add(response.getStatus());
                return null;
            }));
        }
        ready.await(5, TimeUnit.SECONDS);
        start.countDown();
        for (Future<?> future : futures) {
            future.get(20, TimeUnit.SECONDS);
        }
        pool.shutdown();

        assertEquals(1, taskIds.size(), "唯一约束应保证并发同键只落一个任务");
        assertEquals(Set.of(TaskStatus.SUCCESS.name()), statuses,
                "赢方执行与败方重放都应得到成功终态，不能读到执行中间态");
        assertEquals(1, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM audit_task", Integer.class));
        assertEquals(1, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM request_record WHERE request_id = 'req-concurrent-1'", Integer.class));
    }

    // ---------------- 比较：可比较与不可比较 ----------------

    @Test
    @DisplayName("比较：输出五项绑定差异与同名指标数值差，不用零代替")
    void compareComparableTasks_bindingAndMetricDiffs() {
        CreateTaskRequest leftRequest = baseRequest();
        leftRequest.setSeed(1L);
        TaskResponse left = service.createOrReplay(leftRequest);

        CreateTaskRequest rightRequest = baseRequest();
        rightRequest.setSeed(2L);
        rightRequest.setCodeVersion("code@changed");
        TaskResponse right = service.createOrReplay(rightRequest);

        CompareResponse compare = service.compare(left.getId(), right.getId());
        assertTrue(compare.isComparable());
        assertTrue(compare.getBindingDiffs().get("seed").isChanged());
        assertTrue(compare.getBindingDiffs().get("codeVersion").isChanged());
        assertFalse(compare.getBindingDiffs().get("configVersion").isChanged());
        assertEquals("1", compare.getBindingDiffs().get("seed").getLeft());
        assertEquals("2", compare.getBindingDiffs().get("seed").getRight());
        assertEquals(4, compare.getMetricDiffs().size());
        // 同名指标存在真实数值差（不同种子），差值=右-左，未用零填充
        double perceptionDiff = compare.getMetricDiffs().get("PERCEPTION").get("objects_detected");
        double leftValue = byStage(left).get("PERCEPTION").getMetrics().get("objects_detected");
        double rightValue = byStage(right).get("PERCEPTION").getMetrics().get("objects_detected");
        assertEquals(rightValue - leftValue, perceptionDiff, 1e-9);
    }

    @Test
    @DisplayName("比较：与失败任务比较时，缺失指标的阶段标记为指标口径不可比较项而非零值")
    void compareWithFailedTask_marksIncomparableMetrics() {
        TaskResponse success = service.createOrReplay(baseRequest());
        TaskResponse failed = failedAtPlanning();

        CompareResponse compare = service.compare(success.getId(), failed.getId());
        assertFalse(compare.isComparable());
        // 规划与控制在失败任务侧无指标，逐项标记
        Set<String> flagged = new HashSet<>();
        compare.getIncomparableMetrics().forEach(item -> {
            flagged.add(item.getStage() + ":" + item.getMetric() + ":" + item.getSide());
        });
        assertTrue(flagged.contains("PLANNING:path_length_m:LEFT_ONLY"));
        assertTrue(flagged.contains("CONTROL:tracking_error_m:LEFT_ONLY"));
        // 感知、定位两阶段两侧都有指标，正常输出差值（可能为 0，因为绑定种子相同）
        assertEquals(0.0, compare.getMetricDiffs().get("PERCEPTION").get("objects_detected"), 1e-9);
    }

    @Test
    @DisplayName("比较：阶段集合不一致时标记具体不可比较阶段")
    void compareWithDifferentStageSet_marksIncomparableStage() {
        TaskResponse left = service.createOrReplay(baseRequest());
        TaskResponse right = service.createOrReplay(baseRequest());

        // 数据层制造阶段集合差异（右侧删除控制阶段行），验证比较边界不臆造零值
        jdbcTemplate.update("DELETE FROM task_stage WHERE task_id = ? AND stage = 'CONTROL'", right.getId());

        CompareResponse compare = service.compare(left.getId(), right.getId());
        assertFalse(compare.isComparable());
        assertTrue(compare.getIncomparableStages().stream().anyMatch(s -> s.contains("CONTROL")));
    }

    // ---------------- 确定性重放 ----------------

    @Test
    @DisplayName("确定性：相同绑定与种子的两次独立任务产生相同阶段摘要、产物SHA与结果哈希")
    void sameBindingsAndSeed_produceIdenticalHashes() {
        TaskResponse first = service.createOrReplay(baseRequest());
        CreateTaskRequest secondRequest = baseRequest();
        secondRequest.setTaskCode("another-task-code");
        TaskResponse second = service.createOrReplay(secondRequest);

        assertEquals(first.getResultHash(), second.getResultHash());
        Map<String, StageResponse> firstStages = byStage(first);
        Map<String, StageResponse> secondStages = byStage(second);
        for (String stage : List.of("PERCEPTION", "LOCALIZATION", "PLANNING", "CONTROL")) {
            assertEquals(firstStages.get(stage).getMetrics(), secondStages.get(stage).getMetrics());
            assertEquals(firstStages.get(stage).getArtifactSha256(),
                    secondStages.get(stage).getArtifactSha256());
        }
    }

    @Test
    @DisplayName("确定性：失败恢复后的结果哈希与相同绑定种子的一次成功运行一致")
    void recoveredResultHash_equalsCleanRunWithSameBindings() {
        CreateTaskRequest failing = baseRequest();
        failing.setFailStage("CONTROL");
        failing.setFailTimes(1);
        TaskResponse failed = service.createOrReplay(failing);
        assertEquals(TaskStatus.FAILED.name(), failed.getStatus());
        TaskResponse recovered = service.recover(failed.getId()).getTask();

        TaskResponse clean = service.createOrReplay(baseRequest());
        assertEquals(clean.getResultHash(), recovered.getResultHash());
    }

    @Test
    @DisplayName("确定性：不同种子产生不同结果哈希")
    void differentSeed_producesDifferentHash() {
        CreateTaskRequest other = baseRequest();
        other.setSeed(777L);
        TaskResponse different = service.createOrReplay(other);
        TaskResponse baseline = service.createOrReplay(baseRequest());
        assertNotEquals(baseline.getResultHash(), different.getResultHash());
    }

    // ---------------- 辅助 ----------------

    private TaskResponse failedAtPlanning() {
        CreateTaskRequest request = baseRequest();
        request.setFailStage("PLANNING");
        request.setFailTimes(1);
        TaskResponse response = service.createOrReplay(request);
        assertEquals(TaskStatus.FAILED.name(), response.getStatus());
        return response;
    }

    private Map<String, StageResponse> byStage(TaskResponse task) {
        Map<String, StageResponse> map = new java.util.LinkedHashMap<>();
        task.getStages().stream()
                .sorted(Comparator.comparingInt(StageResponse::getSeqNo))
                .forEach(stage -> map.put(stage.getStage(), stage));
        return map;
    }
}
