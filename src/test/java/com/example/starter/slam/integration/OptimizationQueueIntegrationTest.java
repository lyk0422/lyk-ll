package com.example.starter.slam.integration;

import com.example.starter.slam.api.EdgeRequest;
import com.example.starter.slam.api.KeyFrameRequest;
import com.example.starter.slam.api.OptimizeRequest;
import com.example.starter.slam.api.ResultView;
import com.example.starter.slam.api.TaskView;
import com.example.starter.slam.domain.EdgeType;
import com.example.starter.slam.domain.TaskStatus;
import com.example.starter.slam.po.OptimizeResultPO;
import com.example.starter.slam.po.OptimizeTaskPO;
import com.example.starter.slam.po.TaskRequestPO;
import com.example.starter.slam.queue.OptimizationQueue;
import com.example.starter.slam.service.GraphService;
import com.example.starter.slam.service.OptimizationService;
import com.example.starter.slam.store.GraphStore;
import com.example.starter.slam.store.TaskStore;
import com.example.starter.slam.testsupport.ControllableOptimizer;
import com.example.starter.slam.testsupport.SlamTestConfiguration;
import com.example.starter.slam.web.ConflictException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 优化任务队列的 H2(MODE=MySQL) 集成测试：任务合并、requestId 幂等/冲突、
 * 失败与原快照重试、运行中出现新版本时的 SUPERSEDED 保护、旧结果不得覆盖新结果，
 * 以及同图并发提交的合并一致性。
 */
@SpringBootTest(
        classes = {com.example.starter.StarterApplication.class, SlamTestConfiguration.class},
        properties = {
                "spring.datasource.url=jdbc:h2:mem:slam_test_queue;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
                "spring.datasource.driver-class-name=org.h2.Driver",
                "spring.datasource.username=sa",
                "spring.datasource.password=",
                "slam.queue.enabled=false",
                "slam.queue.max-attempts=3"
        })
class OptimizationQueueIntegrationTest {

    private static final List<Double> INFO =
            List.of(100.0, 0.0, 0.0, 0.0, 100.0, 0.0, 0.0, 0.0, 100.0);
    private static final Duration WAIT = Duration.ofSeconds(10);

    @Autowired
    private GraphService graphService;
    @Autowired
    private OptimizationService optimizationService;
    @Autowired
    private OptimizationQueue queue;
    @Autowired
    private TaskStore taskStore;
    @Autowired
    private GraphStore graphStore;
    @Autowired
    private ControllableOptimizer optimizer;
    @Autowired
    private com.example.starter.slam.optim.SnapshotCodec snapshotCodec;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void clean() {
        optimizer.reset();
        jdbcTemplate.update("DELETE FROM optimize_result");
        jdbcTemplate.update("DELETE FROM task_request");
        jdbcTemplate.update("DELETE FROM optimize_task");
        jdbcTemplate.update("DELETE FROM edge");
        jdbcTemplate.update("DELETE FROM key_frame");
        jdbcTemplate.update("DELETE FROM pose_graph");
    }

    private String seedChainGraph(String graphId, int frames) {
        // 关键帧/边 ID 全局唯一，按图 ID 生成前缀避免不同图之间冲突
        String prefix = graphId.replace('-', '_');
        for (int i = 0; i < frames; i++) {
            String frameId = prefix + "_f" + i;
            graphService.putFrame(graphId, frameId,
                    new KeyFrameRequest(frameId, "odom", 1, List.of((double) i, 0.0, 0.0)));
        }
        for (int i = 0; i < frames - 1; i++) {
            String edgeId = prefix + "_e" + i;
            graphService.putEdge(graphId, edgeId, new EdgeRequest(
                    edgeId, "edge-req-" + edgeId, "odom", 1, EdgeType.ODOMETRY,
                    prefix + "_f" + i, prefix + "_f" + (i + 1),
                    List.of(1.0, 0.0, 0.0), INFO, null, null));
        }
        return graphId;
    }

    private void addLoopEdge(String graphId, String edgeId, String from, String to) {
        graphService.putEdge(graphId, edgeId, new EdgeRequest(
                edgeId, "req-" + edgeId, "loop-detector", 1, EdgeType.LOOP,
                from, to, List.of(1.0, 0.0, 0.0), INFO, 0.95, 0.05));
    }

    @Test
    void multiplePendingRequestsOnSameGraphMergeIntoOneTaskCoveringHighestVersion() {
        seedChainGraph("g-merge", 3);

        TaskView first = optimizationService.submit(new OptimizeRequest("req-A", "g-merge"));
        long versionAtFirst = first.targetVersion();

        // 增加一条有效回环边提升图版本后再次提交
        addLoopEdge("g-merge", "loop-1", "g_merge_f0", "g_merge_f2");
        TaskView second = optimizationService.submit(new OptimizeRequest("req-B", "g-merge"));
        TaskView third = optimizationService.submit(new OptimizeRequest("req-C", "g-merge"));

        assertEquals(first.taskId(), second.taskId());
        assertEquals(first.taskId(), third.taskId());
        assertEquals(versionAtFirst + 1, second.targetVersion());
        // 合并任务保留全部被合并请求的查询关系（third 视图在 req-C 插入后构造，含全部三个请求）
        assertTrue(third.requestIds().containsAll(List.of("req-A", "req-B", "req-C")));
        assertEquals(3, third.requestIds().size());
        assertEquals(1, taskCount());

        // 被合并请求通过 requestId 也能查询到承接任务
        TaskView byRequestB = optimizationService.getByRequestId("req-B");
        assertEquals(first.taskId(), byRequestB.taskId());
        assertEquals(second.taskId(), byRequestB.mergedIntoTaskId());

        // 执行合并任务后结果基于最高版本
        assertTrue(queue.processOne());
        TaskView done = optimizationService.getTask(first.taskId());
        assertEquals(TaskStatus.SUCCEEDED, done.status());
        assertEquals(versionAtFirst + 1, done.resultVersion());
    }

    @Test
    void duplicateRequestIdReturnsSameTaskWithoutCreatingAnother() {
        seedChainGraph("g-idem", 2);
        TaskView first = optimizationService.submit(new OptimizeRequest("req-dup", "g-idem"));
        TaskView again = optimizationService.submit(new OptimizeRequest("req-dup", "g-idem"));
        assertEquals(first.taskId(), again.taskId());
        assertEquals(1, taskCount());
        assertEquals(1, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM task_request", Integer.class));
    }

    @Test
    void sameRequestIdOnDifferentGraphReturns409() {
        seedChainGraph("g-a", 2);
        seedChainGraph("g-b", 2);
        optimizationService.submit(new OptimizeRequest("req-x", "g-a"));
        assertThrows(ConflictException.class,
                () -> optimizationService.submit(new OptimizeRequest("req-x", "g-b")));
    }

    @Test
    void failedTaskKeepsSnapshotAndReasonAndRetryUsesSameSnapshot() {
        seedChainGraph("g-retry", 3);
        TaskView submitted = optimizationService.submit(new OptimizeRequest("req-fail", "g-retry"));
        String taskId = submitted.taskId();
        long targetVersion = submitted.targetVersion();

        optimizer.failNext(1);
        assertTrue(queue.processOne());

        TaskView failed = optimizationService.getTask(taskId);
        assertEquals(TaskStatus.FAILED_RETRYABLE, failed.status());
        assertEquals(1, failed.attempts());
        assertNotNull(failed.failureReason());
        assertTrue(failed.failureReason().contains("simulated transient optimization failure"));
        assertNotNull(failed.snapshotSummary());
        assertTrue(failed.snapshotSummary().contains("version=" + targetVersion));

        // 失败后新增有效边；显式重试必须使用原快照（版本不变）
        addLoopEdge("g-retry", "loop-new", "g_retry_f0", "g_retry_f2");
        long versionAfterGrowth = graphStore.currentVersion("g-retry");
        assertTrue(versionAfterGrowth > targetVersion);

        TaskView retried = optimizationService.retry(taskId);
        assertEquals(TaskStatus.PENDING, retried.status());
        assertTrue(queue.processOne());

        TaskView succeeded = optimizationService.getTask(taskId);
        assertEquals(TaskStatus.SUCCEEDED, succeeded.status());
        assertEquals(2, succeeded.attempts());
        // 结果仍基于原快照版本，而非新图版本
        assertEquals(targetVersion, succeeded.resultVersion());

        OptimizeResultPO result = taskStore.findResultByTask(taskId);
        assertEquals(targetVersion, result.graphVersion());
    }

    @Test
    void retryOnNonFailedTaskReturns409() {
        seedChainGraph("g-state", 2);
        TaskView task = optimizationService.submit(new OptimizeRequest("req-s", "g-state"));
        assertThrows(ConflictException.class, () -> optimizationService.retry(task.taskId()));
    }

    @Test
    void taskExhaustingAttemptsMustCreateNewTaskForFreshSnapshot() {
        seedChainGraph("g-max", 2);
        TaskView task = optimizationService.submit(new OptimizeRequest("req-m", "g-max"));
        String taskId = task.taskId();

        optimizer.failNext(3);
        for (int expectedAttempt = 1; expectedAttempt <= 3; expectedAttempt++) {
            assertTrue(queue.processOne());
            TaskView current = optimizationService.getTask(taskId);
            assertEquals(expectedAttempt, current.attempts());
            if (expectedAttempt < 3) {
                optimizationService.retry(taskId);
            }
        }
        TaskView exhausted = optimizationService.getTask(taskId);
        assertEquals(TaskStatus.FAILED_RETRYABLE, exhausted.status());
        assertEquals(3, exhausted.attempts());
        assertThrows(ConflictException.class, () -> optimizationService.retry(taskId));
    }

    @Test
    void runningOldTaskDoesNotMergeAndNewerResultWinsWhenOldFinishesLater() {
        seedChainGraph("g-race", 3);
        long oldVersion = graphStore.currentVersion("g-race");

        // 旧请求提交；将其领取为 RUNNING 并在运行开始时固化旧版本快照
        TaskView oldTask = optimizationService.submit(new OptimizeRequest("req-old", "g-race"));
        assertTrue(queue.processOne());
        // 旧任务已在旧版本快照上执行完成（无更新结果时正常成功）
        assertEquals(TaskStatus.SUCCEEDED, optimizationService.getTask(oldTask.taskId()).status());
        assertEquals(oldVersion, optimizationService.getTask(oldTask.taskId()).resultVersion());

        // 运行后出现新版本（更多有效边），新请求不能与已运行任务合并
        addLoopEdge("g-race", "loop-race", "g_race_f0", "g_race_f2");
        long newVersion = graphStore.currentVersion("g-race");
        assertTrue(newVersion > oldVersion);
        TaskView newTask = optimizationService.submit(new OptimizeRequest("req-new", "g-race"));
        assertFalse(oldTask.taskId().equals(newTask.taskId()));
        assertTrue(queue.processOne());

        // 两个结果行各自独立，新版本成为最新结果，旧结果未被覆盖
        assertEquals(newVersion, optimizationService.getTask(newTask.taskId()).resultVersion());
        assertEquals(newVersion, taskStore.findLatestSucceededResult("g-race").graphVersion());
        assertNotNull(taskStore.findResultByTask(oldTask.taskId()));
        assertEquals(oldVersion, taskStore.findResultByTask(oldTask.taskId()).graphVersion());
        assertEquals(2, resultCount());
    }

    @Test
    void oldTaskFinishingAfterNewerResultIsMarkedSuperseded() {
        seedChainGraph("g-super", 3);
        long oldVersion = graphStore.currentVersion("g-super");

        // 模拟一个已领取、已在旧版本固化快照、但尚未完成的运行中任务
        String oldRunningId = createRunningTaskWithCurrentSnapshot("g-super", oldVersion);

        // 运行期间写入更多有效边，新版本任务先成功落库
        addLoopEdge("g-super", "loop-super", "g_super_f0", "g_super_f2");
        long newVersion = graphStore.currentVersion("g-super");
        assertTrue(newVersion > oldVersion);
        TaskView newerTask = optimizationService.submit(new OptimizeRequest("req-sn", "g-super"));
        assertTrue(queue.processOne());
        assertEquals(TaskStatus.SUCCEEDED, optimizationService.getTask(newerTask.taskId()).status());
        assertEquals(newVersion, taskStore.findLatestSucceededResult("g-super").graphVersion());

        // 旧任务此时才带着旧快照完成：必须被标记 SUPERSEDED，且不写结果、不覆盖新结果
        queue.execute(oldRunningId, 1);
        OptimizeTaskPO oldLike = taskStore.findTask(oldRunningId);
        assertEquals(TaskStatus.SUPERSEDED, oldLike.status());
        assertNull(taskStore.findResultByTask(oldRunningId));
        assertEquals(newVersion, taskStore.findLatestSucceededResult("g-super").graphVersion());
        assertEquals(1, resultCount());
    }

    /**
     * 构造"领取后固化了当前图快照、仍处于 RUNNING"的任务，用于精确驱动旧结果晚到的场景。
     */
    private String createRunningTaskWithCurrentSnapshot(String graphId, long version) {
        String taskId = java.util.UUID.randomUUID().toString();
        Instant now = Instant.now();
        OptimizeTaskPO task = new OptimizeTaskPO(taskId, graphId, version, TaskStatus.RUNNING,
                1, null, null, null, null, now, now, now, null);
        taskStore.insertTask(task);
        // 固化快照后再让图继续演进，保证旧任务使用的是旧版本输入
        com.example.starter.slam.optim.GraphSnapshot snapshot =
                new com.example.starter.slam.optim.SnapshotFactory(graphStore).build(graphId);
        taskStore.attachSnapshot(taskId, snapshotCodec.write(snapshot), snapshot.summary(), Instant.now());
        return taskId;
    }

    @Test
    void concurrentSubmitsOnSameGraphProduceConsistentMergedState() throws Exception {
        seedChainGraph("g-conc", 2);
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<TaskView>> futures = new java.util.ArrayList<>();
        for (int i = 0; i < threads; i++) {
            final int index = i;
            futures.add(pool.submit(() -> {
                start.await();
                return optimizationService.submit(new OptimizeRequest("creq-" + index, "g-conc"));
            }));
        }
        start.countDown();
        String survivingTaskId = null;
        for (Future<TaskView> future : futures) {
            TaskView view = future.get(WAIT.toSeconds(), TimeUnit.SECONDS);
            if (survivingTaskId == null) {
                survivingTaskId = view.taskId();
            } else {
                assertEquals(survivingTaskId, view.taskId());
            }
        }
        pool.shutdown();

        assertEquals(1, taskCount());
        TaskView surviving = optimizationService.getTask(survivingTaskId);
        assertEquals(threads, surviving.requestIds().size());
        assertEquals(TaskStatus.PENDING, surviving.status());
    }

    @Test
    void asyncWorkerPicksTaskAndProducesDeterministicResultViaEnabledQueue() {
        // 本测试类禁用了自动 worker，这里验证手动驱动产出的结果内容完整可用
        seedChainGraph("g-result", 3);
        TaskView task = optimizationService.submit(new OptimizeRequest("req-r", "g-result"));
        assertTrue(queue.processOne());
        ResultView result = optimizationService.getResult(task.taskId());
        assertEquals(List.of("g_result_f0", "g_result_f1", "g_result_f2"), result.frameIds());
        assertEquals(3, result.optimizedPoses().size());
        assertEquals(graphStore.currentVersion("g-result"), result.graphVersion());
        // 首帧锚定
        assertEquals(0.0, result.optimizedPoses().get(0).get(0), 1e-12);
    }

    private int taskCount() {
        Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM optimize_task", Integer.class);
        return count == null ? 0 : count;
    }

    private int resultCount() {
        Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM optimize_result", Integer.class);
        return count == null ? 0 : count;
    }
}
