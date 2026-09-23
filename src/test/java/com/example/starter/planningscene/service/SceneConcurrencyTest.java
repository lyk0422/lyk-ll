package com.example.starter.planningscene.service;

import com.example.starter.planningscene.dto.CommitResponse;
import com.example.starter.planningscene.dto.SceneUpdateRequest;
import com.example.starter.planningscene.dto.SceneViewResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static com.example.starter.planningscene.SceneTestFixtures.addWorld;
import static com.example.starter.planningscene.SceneTestFixtures.joint;
import static com.example.starter.planningscene.SceneTestFixtures.pose;
import static com.example.starter.planningscene.SceneTestFixtures.request;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 乐观并发、幂等键与历史读不阻塞写的集成测试（真实 H2 事务 + 真实线程并发）。
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:concurrency_test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=30000",
        "spring.datasource.hikari.maximum-pool-size=10"
})
class SceneConcurrencyTest {

    @Autowired
    private SceneService sceneService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void resetScene() {
        jdbcTemplate.execute("DELETE FROM scene_request");
        jdbcTemplate.execute("DELETE FROM scene_object");
        jdbcTemplate.execute("DELETE FROM scene_joint");
        jdbcTemplate.execute("DELETE FROM scene_robot_state");
        jdbcTemplate.execute("DELETE FROM scene_meta");
        jdbcTemplate.update("INSERT INTO scene_meta (id, current_version) VALUES (0, 0)");
        jdbcTemplate.update("INSERT INTO scene_robot_state "
                + "(version, pos_x, pos_y, pos_z, qx, qy, qz, qw) VALUES (0,0,0,0,0,0,0,1)");
        jdbcTemplate.update("INSERT INTO scene_joint (version, joint_name, position, lower_bound, upper_bound) "
                + "SELECT 0, joint_name, 0.0, lower_bound, upper_bound FROM robot_joint");
    }

    @Test
    @SuppressWarnings("unchecked")
    void concurrentCommitsOnSameVersionExactlyOneSucceeds() throws Exception {
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        Future<Object>[] futures = new Future[threads];
        for (int i = 0; i < threads; i++) {
            SceneUpdateRequest req = request("req-concurrent-" + i, 0, null, List.of(),
                    List.of(addWorld("box-" + i, "world", "{}", pose(i, 0, 0))));
            futures[i] = pool.submit(() -> {
                start.await();
                return sceneService.commit(req);
            });
        }
        start.countDown();

        AtomicInteger success = new AtomicInteger();
        AtomicInteger conflicts = new AtomicInteger();
        for (Future<Object> future : futures) {
            try {
                Object result = future.get(30, TimeUnit.SECONDS);
                assertThat(result).isInstanceOf(CommitResponse.class);
                success.incrementAndGet();
            } catch (Exception e) {
                Throwable cause = e.getCause() != null ? e.getCause() : e;
                assertThat(cause).isInstanceOf(SceneException.VersionConflict.class);
                conflicts.incrementAndGet();
            }
        }
        pool.shutdown();

        assertThat(success.get()).isEqualTo(1);
        assertThat(conflicts.get()).isEqualTo(threads - 1);
        assertThat(sceneService.currentView().version()).isEqualTo(1);
        assertThat(sceneService.currentView().objects()).hasSize(1);
    }

    @Test
    void versionConflictReportsCurrentVersionAndChangedIds() {
        sceneService.commit(request("req-v1", 0, null,
                List.of(joint("joint_1", 0.5)),
                List.of(addWorld("box", "world", "{}", pose(0, 0, 0)))));

        SceneUpdateRequest stale = request("req-stale", 0, null,
                List.of(joint("joint_2", 0.1)),
                List.of(addWorld("other", "world", "{}", pose(0, 0, 0))));
        assertThatThrownBy(() -> sceneService.commit(stale))
                .isInstanceOf(SceneException.VersionConflict.class)
                .satisfies(ex -> {
                    SceneException.VersionConflict conflict = (SceneException.VersionConflict) ex;
                    assertThat(conflict.getCurrentVersion()).isEqualTo(1);
                    assertThat(conflict.getExpectedVersion()).isZero();
                    assertThat(conflict.getChangedObjectIds()).containsExactly("box");
                    assertThat(conflict.getChangedJointNames()).containsExactly("joint_1");
                });

        // 冲突请求不得产生任何写入
        assertThat(sceneService.currentView().version()).isEqualTo(1);
        assertThat(sceneService.currentView().objects())
                .extracting(SceneViewResponse.ObjectView::objectId).containsExactly("box");
    }

    @Test
    void sameRequestIdSameParamsReplaysOriginalAndDifferentParamsConflict() {
        SceneUpdateRequest first = request("req-idempotent", 0, null, List.of(),
                List.of(addWorld("keg", "world", "{}", pose(0, 0, 0))));
        CommitResponse committed = sceneService.commit(first);
        assertThat(committed.replayed()).isFalse();
        assertThat(committed.version()).isEqualTo(1);

        // 同键同参：返回原结果（标记重放），不产生新版本
        CommitResponse replayed = sceneService.commit(request("req-idempotent", 0, null, List.of(),
                List.of(addWorld("keg", "world", "{}", pose(0, 0, 0)))));
        assertThat(replayed.replayed()).isTrue();
        assertThat(replayed.version()).isEqualTo(1);
        assertThat(sceneService.currentView().version()).isEqualTo(1);

        // 同键异参：409
        SceneUpdateRequest different = request("req-idempotent", 0, null, List.of(),
                List.of(addWorld("keg", "base_link", "{\"shape\":\"sphere\"}", pose(1, 0, 0))));
        assertThatThrownBy(() -> sceneService.commit(different))
                .isInstanceOf(SceneException.IdempotencyConflict.class);
        assertThat(sceneService.currentView().version()).isEqualTo(1);
    }

    @Test
    void historySnapshotReadsContinueWhileWritesCommit() throws Exception {
        int commits = 12;
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);

        // 写入方：顺序提交多个版本
        Future<?> writer = pool.submit(() -> {
            try {
                start.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError(e);
            }
            for (int v = 1; v <= commits; v++) {
                sceneService.commit(request("req-history-write-" + v, v - 1, null, List.of(),
                        List.of(addWorld("obj-" + v, "world", "{}", pose(0, 0, 0)))));
            }
        });

        // 读取方：等待版本 1 出现后反复读取历史版本 1，统计成功次数
        Future<Integer> reader = pool.submit(() -> {
            start.await();
            AtomicInteger reads = new AtomicInteger();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
            while (System.nanoTime() < deadline) {
                if (sceneService.currentView().version() >= 1) {
                    SceneViewResponse v1 = sceneService.snapshot(1);
                    if (v1.version() == 1 && v1.objects().size() == 1) {
                        reads.incrementAndGet();
                    }
                }
                if (reads.get() > 50) {
                    break;
                }
            }
            return reads.get();
        });

        start.countDown();
        writer.get(30, TimeUnit.SECONDS);
        int readCount = reader.get(30, TimeUnit.SECONDS);
        pool.shutdown();

        assertThat(readCount).isGreaterThan(50);
        assertThat(sceneService.currentView().version()).isEqualTo(commits);
        // 任意历史版本仍可读取且不可变
        assertThat(sceneService.snapshot(1).objects())
                .extracting(SceneViewResponse.ObjectView::objectId).containsExactly("obj-1");
        assertThat(sceneService.snapshot(7).objects())
                .extracting(SceneViewResponse.ObjectView::objectId).hasSize(7);
    }
}
