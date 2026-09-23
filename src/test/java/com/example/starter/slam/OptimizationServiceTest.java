package com.example.starter.slam;

import com.example.starter.slam.domain.OptimizationResult;
import com.example.starter.slam.domain.OptimizationTask;
import com.example.starter.slam.model.TaskStatus;
import com.example.starter.slam.service.GraphWriteService;
import com.example.starter.slam.service.OptimizationService;
import com.example.starter.slam.web.ConflictException;
import com.example.starter.slam.web.NotFoundException;
import com.example.starter.slam.web.UnprocessableException;
import com.example.starter.slam.web.dto.EdgeRequest;
import com.example.starter.slam.web.dto.KeyframeRequest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

/**
 * 优化队列核心语义：
 * 待执行请求合并、幂等提交、运行中新版本结果导致旧任务 SUPERSEDED、
 * 可重试失败保存原因并重试用原快照、并发提交的行锁合并。
 */
class OptimizationServiceTest extends AbstractSlamIntegrationTest {

    @Autowired
    private GraphWriteService graphWriteService;

    @Autowired
    private OptimizationService optimizationService;

    private static KeyframeRequest keyframe(String id, String requestId) {
        JsonNode measurement = JsonNodeFactory.instance.objectNode().put("scanId", id);
        return new KeyframeRequest(id, "lidar-a", 0, 1.0, 2.0, 0.1, measurement, requestId);
    }

    private static EdgeRequest odometry(String id, String from, String to, String requestId) {
        return new EdgeRequest(id, "ODOMETRY", "lidar-a", 0, from, to,
                0.5, 0.0, 0.0, goodCovariance(), null, null, requestId);
    }

    /**
     * 构建 v1=kf1, v2=kf2, v3=e1(kf1->kf2) 的图。
     */
    private void buildThreeVersionGraph() {
        graphWriteService.writeKeyframe(keyframe("kf1", "req-kf1"));
        graphWriteService.writeKeyframe(keyframe("kf2", "req-kf2"));
        graphWriteService.writeEdge(odometry("e1", "kf1", "kf2", "req-e1"));
    }

    @Test
    void submitOnEmptyGraphIsRejected() {
        assertThatThrownBy(() -> optimizationService.submit("opt-empty"))
                .isInstanceOf(UnprocessableException.class)
                .hasMessageContaining("empty");
    }

    @Test
    void duplicateSubmitIsIdempotent() {
        buildThreeVersionGraph();
        OptimizationTask first = optimizationService.submit("opt-a");
        OptimizationTask second = optimizationService.submit("opt-a");

        assertThat(second.id()).isEqualTo(first.id());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM opt_tasks", Integer.class)).isEqualTo(1);
    }

    @Test
    void pendingRequestsMergeIntoOneTaskCoveringHighestVersion() {
        // 场景：v3 时提交 opt-a（尚未执行），图增长到 v5 后提交 opt-b 应被合并进 opt-a，
        // 保留任务的目标版本提升为 5，最终只执行一次且覆盖最高版本快照。
        buildThreeVersionGraph();

        OptimizationTask taskA = optimizationService.submit("opt-a");
        assertThat(taskA.targetGraphVersion()).isEqualTo(3);

        graphWriteService.writeKeyframe(keyframe("kf3", "req-kf3"));
        graphWriteService.writeEdge(odometry("e2", "kf2", "kf3", "req-e2"));

        OptimizationTask taskB = optimizationService.submit("opt-b");

        // B 被合并，保留对 A 的查询关系
        assertThat(taskB.status()).isEqualTo(TaskStatus.MERGED);
        assertThat(taskB.mergedIntoId()).isEqualTo(taskA.id());

        OptimizationTask survivor = optimizationService.getTask(taskA.id());
        assertThat(survivor.status()).isEqualTo(TaskStatus.PENDING);
        assertThat(survivor.targetGraphVersion()).isEqualTo(5);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM opt_tasks WHERE status='PENDING' AND merged_into_id IS NULL",
                Integer.class)).isEqualTo(1);

        // 合并后只执行一次，且使用最高版本 v5 快照（2 条有效边 → cost = 5 + 2*0.01）
        optimizationService.drainQueue();
        await().atMost(10, TimeUnit.SECONDS).untilAsserted(() ->
                assertThat(optimizationService.getTask(taskA.id()).status())
                        .isEqualTo(TaskStatus.SUCCEEDED));
        assertThat(optimizer.getCallCount()).isEqualTo(1);

        OptimizationResult resultA = optimizationService.getResult(taskA.id());
        OptimizationResult resultViaB = optimizationService.getResult(taskB.id());
        assertThat(resultA.id()).isEqualTo(resultViaB.id());
        assertThat(resultA.graphVersion()).isEqualTo(5);
        assertThat(resultA.finalCost()).isEqualTo(5.02, org.assertj.core.data.Offset.offset(1e-9));
    }

    @Test
    void runningOldTaskDoesNotOverwriteNewerResultAndBecomesSuperseded() throws Exception {
        buildThreeVersionGraph(); // v3
        OptimizationTask taskA = optimizationService.submit("opt-a");

        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        optimizer.blockNextCall(entered, release);

        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            // 后台领取 A：优化器进入门闩，任务停留在 RUNNING
            pool.execute(optimizationService::drainQueue);
            assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();
            await().atMost(10, TimeUnit.SECONDS).untilAsserted(() ->
                    assertThat(optimizationService.getTask(taskA.id()).status())
                            .isEqualTo(TaskStatus.RUNNING));

            // 运行期间图增长到 v5 并提交新请求 B（运行中的任务不参与合并，B 独立执行）
            graphWriteService.writeKeyframe(keyframe("kf3", "req-kf3"));
            graphWriteService.writeEdge(odometry("e2", "kf2", "kf3", "req-e2"));
            OptimizationTask taskB = optimizationService.submit("opt-b");
            assertThat(taskB.targetGraphVersion()).isEqualTo(5);

            // B 无门闩立即执行完成并落库 v5 结果
            optimizationService.drainQueue();
            await().atMost(10, TimeUnit.SECONDS).untilAsserted(() ->
                    assertThat(optimizationService.getTask(taskB.id()).status())
                            .isEqualTo(TaskStatus.SUCCEEDED));
            OptimizationResult newer = optimizationService.getResult(taskB.id());
            assertThat(newer.graphVersion()).isEqualTo(5);

            // 释放旧任务 A：发现已有更高版本结果，标记 SUPERSEDED 且不写入/覆盖结果
            release.countDown();
            await().atMost(10, TimeUnit.SECONDS).untilAsserted(() ->
                    assertThat(optimizationService.getTask(taskA.id()).status())
                            .isEqualTo(TaskStatus.SUPERSEDED));

            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM opt_results", Integer.class)).isEqualTo(1);
            OptimizationResult stillNewer = optimizationService.getResult(taskB.id());
            assertThat(stillNewer.taskId()).isEqualTo(taskB.id());
            assertThat(stillNewer.id()).isEqualTo(newer.id());
            assertThatThrownBy(() -> optimizationService.getResult(taskA.id()))
                    .isInstanceOf(NotFoundException.class);
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    void failedTaskStoresReasonAndRetryUsesOriginalSnapshot() {
        buildThreeVersionGraph(); // v3：1 条有效边
        OptimizationTask task = optimizationService.submit("opt-retry");

        optimizer.failNextCallWith("simulated optimizer divergence");
        optimizationService.drainQueue();

        await().atMost(10, TimeUnit.SECONDS).untilAsserted(() ->
                assertThat(optimizationService.getTask(task.id()).status())
                        .isEqualTo(TaskStatus.RETRYABLE_FAILED));
        OptimizationTask failed = optimizationService.getTask(task.id());
        assertThat(failed.failureReason()).contains("simulated optimizer divergence");
        assertThat(failed.retryCount()).isZero();
        String originalDigest = failed.snapshotDigest();
        assertThat(failed.snapshotJson()).isNotBlank();

        // 失败期间图增长到 v5；明确要求重试不得换快照
        graphWriteService.writeKeyframe(keyframe("kf3", "req-kf3"));
        graphWriteService.writeEdge(odometry("e2", "kf2", "kf3", "req-e2"));

        OptimizationTask reset = optimizationService.retry(task.id());
        assertThat(reset.status()).isEqualTo(TaskStatus.PENDING);
        assertThat(reset.retryCount()).isEqualTo(1);
        assertThat(reset.snapshotDigest()).isEqualTo(originalDigest);

        optimizationService.drainQueue();
        await().atMost(10, TimeUnit.SECONDS).untilAsserted(() ->
                assertThat(optimizationService.getTask(task.id()).status())
                        .isEqualTo(TaskStatus.SUCCEEDED));

        // 两次优化看到同一快照摘要；结果基于原 v3 快照而非当前 v5 图
        List<String> digests = optimizer.getSeenDigests();
        assertThat(digests).hasSize(2);
        assertThat(digests.get(0)).isEqualTo(digests.get(1)).isEqualTo(originalDigest);

        OptimizationResult result = optimizationService.getResult(task.id());
        assertThat(result.graphVersion()).isEqualTo(3);
        assertThat(result.finalCost()).isEqualTo(3.01, org.assertj.core.data.Offset.offset(1e-9));
        assertThat(optimizationService.getTask(task.id()).retryCount()).isEqualTo(1);
    }

    @Test
    void retryOnNonFailedTaskIsConflict() {
        buildThreeVersionGraph();
        OptimizationTask task = optimizationService.submit("opt-x");
        // PENDING 状态不可重试
        assertThatThrownBy(() -> optimizationService.retry(task.id()))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("PENDING");
    }

    @Test
    void concurrentSubmissionsMergeViaRowLock() throws Exception {
        buildThreeVersionGraph();

        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicReference<Throwable> firstError = new AtomicReference<>();
        try {
            List<java.util.concurrent.Future<?>> futures = new java.util.ArrayList<>();
            for (int i = 0; i < threads; i++) {
                String requestId = "opt-concurrent-" + i;
                futures.add(pool.submit(() -> {
                    try {
                        start.await();
                        optimizationService.submit(requestId);
                    } catch (Throwable t) {
                        firstError.compareAndSet(null, t);
                    }
                }));
            }
            start.countDown();
            for (java.util.concurrent.Future<?> f : futures) {
                f.get(15, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }
        assertThat(firstError.get()).isNull();

        Integer survivorCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM opt_tasks WHERE status='PENDING' AND merged_into_id IS NULL",
                Integer.class);
        Integer mergedCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM opt_tasks WHERE status='MERGED'", Integer.class);
        assertThat(survivorCount).isEqualTo(1);
        assertThat(mergedCount).isEqualTo(threads - 1);

        // 全部被合并请求指向同一个保留任务
        Integer distinctTargets = jdbc.queryForObject(
                "SELECT COUNT(DISTINCT merged_into_id) FROM opt_tasks WHERE status='MERGED'",
                Integer.class);
        assertThat(distinctTargets).isEqualTo(1);
    }
}
