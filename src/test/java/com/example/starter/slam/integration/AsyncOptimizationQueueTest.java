package com.example.starter.slam.integration;

import com.example.starter.slam.api.EdgeRequest;
import com.example.starter.slam.api.KeyFrameRequest;
import com.example.starter.slam.api.OptimizeRequest;
import com.example.starter.slam.api.TaskView;
import com.example.starter.slam.domain.EdgeType;
import com.example.starter.slam.domain.TaskStatus;
import com.example.starter.slam.service.GraphService;
import com.example.starter.slam.service.OptimizationService;
import com.example.starter.slam.testsupport.ControllableOptimizer;
import com.example.starter.slam.testsupport.SlamTestConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * 启用真实后台工作线程的异步队列测试：提交后无需外部驱动即可完成；
 * 可重试失败可被观察到，显式重试后仍按原快照成功。
 */
@SpringBootTest(
        classes = {com.example.starter.StarterApplication.class, SlamTestConfiguration.class},
        properties = {
                "spring.datasource.url=jdbc:h2:mem:slam_test_async;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
                "spring.datasource.driver-class-name=org.h2.Driver",
                "spring.datasource.username=sa",
                "spring.datasource.password=",
                "slam.queue.enabled=true",
                "slam.queue.workers=1",
                "slam.queue.poll-millis=20",
                "slam.queue.max-attempts=3"
        })
class AsyncOptimizationQueueTest {

    private static final List<Double> INFO =
            List.of(100.0, 0.0, 0.0, 0.0, 100.0, 0.0, 0.0, 0.0, 100.0);

    @Autowired
    private GraphService graphService;
    @Autowired
    private OptimizationService optimizationService;
    @Autowired
    private ControllableOptimizer optimizer;
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

    private void seedChain(String graphId) {
        for (int i = 0; i < 3; i++) {
            String id = "af" + i;
            graphService.putFrame(graphId, id,
                    new KeyFrameRequest(id, "odom", 1, List.of((double) i, 0.0, 0.0)));
        }
        for (int i = 0; i < 2; i++) {
            String edgeId = "ae" + i;
            graphService.putEdge(graphId, edgeId, new EdgeRequest(
                    edgeId, "areq-" + edgeId, "odom", 1, EdgeType.ODOMETRY,
                    "af" + i, "af" + (i + 1), List.of(1.0, 0.0, 0.0), INFO, null, null));
        }
    }

    @Test
    void submittedTaskIsPickedUpByWorkerAndSucceedsAsynchronously() {
        seedChain("g-async-ok");
        TaskView task = optimizationService.submit(new OptimizeRequest("req-async-ok", "g-async-ok"));

        await().atMost(10, SECONDS).untilAsserted(() ->
                assertEquals(TaskStatus.SUCCEEDED,
                        optimizationService.getTask(task.taskId()).status()));

        TaskView done = optimizationService.getTask(task.taskId());
        assertNotNull(done.resultVersion());
        assertNotNull(optimizationService.getResult(task.taskId()).resultId());
    }

    @Test
    void failedTaskCanBeRetriedExplicitlyAndSucceedsOnSameSnapshot() {
        seedChain("g-async-retry");
        // 必须在提交（唤醒 worker）之前编排首次失败
        optimizer.failNext(1);
        TaskView task = optimizationService.submit(new OptimizeRequest("req-async-retry", "g-async-retry"));

        await().atMost(10, SECONDS).untilAsserted(() ->
                assertEquals(TaskStatus.FAILED_RETRYABLE,
                        optimizationService.getTask(task.taskId()).status()));

        TaskView failed = optimizationService.getTask(task.taskId());
        assertEquals(1, failed.attempts());
        assertNotNull(failed.snapshotSummary());

        optimizationService.retry(task.taskId());

        await().atMost(10, SECONDS).untilAsserted(() ->
                assertEquals(TaskStatus.SUCCEEDED,
                        optimizationService.getTask(task.taskId()).status()));

        TaskView done = optimizationService.getTask(task.taskId());
        assertEquals(2, done.attempts());
        // 重试复用原快照，结果版本等于首次提交时的目标版本
        assertEquals(failed.targetVersion(), done.resultVersion());
    }
}
