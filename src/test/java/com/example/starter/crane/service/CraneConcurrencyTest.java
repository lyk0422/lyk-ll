package com.example.starter.crane.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.starter.crane.AbstractCraneIntegrationTest;
import com.example.starter.crane.ConflictException;
import com.example.starter.crane.api.AuthorizationRequest;
import com.example.starter.crane.api.AuthorizationView;
import com.example.starter.crane.api.PoseAcceptance;
import com.example.starter.crane.api.PoseRequest;
import com.example.starter.crane.api.ReleaseRequest;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 真实并发边界测试（H2 行锁 + 唯一约束，不使用 mock/Map）：
 * 同区同时段并发唯一授权、同 requestId 并发幂等、同时间戳并发位姿去重、释放与位姿判罚竞态唯一结论。
 */
class CraneConcurrencyTest extends AbstractCraneIntegrationTest {

    private static final Instant W0 = Instant.parse("2026-09-25T10:00:00Z");
    private static final Instant W1 = Instant.parse("2026-09-25T10:10:00Z");

    @Autowired
    private AuthorizationService authorizationService;
    @Autowired
    private PoseProcessingService poseProcessingService;

    /** 并发起跑：让所有线程尽可能同时进入临界区。 */
    private <T> List<Future<T>> runParallel(int threads, TaskFactory<T> factory) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            CountDownLatch ready = new CountDownLatch(threads);
            CountDownLatch start = new CountDownLatch(1);
            List<Future<T>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                final int idx = i;
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    start.await(10, TimeUnit.SECONDS);
                    return factory.run(idx);
                }));
            }
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            for (Future<T> f : futures) {
                f.get(30, TimeUnit.SECONDS);
            }
            return futures;
        } finally {
            pool.shutdownNow();
            assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    private interface TaskFactory<T> {
        T run(int idx) throws Exception;
    }

    private record AuthOutcome(boolean granted, boolean conflict, boolean error) {
    }

    @Test
    void concurrent_requests_same_zone_same_window_only_one_granted() throws Exception {
        int threads = 8;
        List<Future<AuthOutcome>> futures = runParallel(threads, idx -> {
            try {
                AuthorizationView v = authorizationService.apply(new AuthorizationRequest(
                        "CC-" + idx, idx % 2 == 0 ? "C-101" : "C-102", "Z-A1",
                        W0, W1, new BigDecimal("1000")));
                return new AuthOutcome("GRANTED".equals(v.result()), false, false);
            } catch (ConflictException c) {
                return new AuthOutcome(false, true, false);
            } catch (Exception e) {
                return new AuthOutcome(false, false, true);
            }
        });

        long granted = 0;
        long conflict = 0;
        for (Future<AuthOutcome> f : futures) {
            AuthOutcome o = f.get();
            assertThat(o.error()).as("不应出现锁超时等系统错误").isFalse();
            if (o.granted()) {
                granted++;
            }
            if (o.conflict()) {
                conflict++;
            }
        }
        assertThat(granted).as("同区同时段只允许一台塔机获得授权").isEqualTo(1);
        assertThat(conflict).isEqualTo(threads - 1);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(1) FROM zone_authorization WHERE zone_code = 'Z-A1' "
                        + "AND result = 'GRANTED' AND status = 'GRANTED'", Integer.class)).isEqualTo(1);
    }

    @Test
    void concurrent_same_request_id_idempotent_single_row() throws Exception {
        int threads = 8;
        AuthorizationRequest shared = new AuthorizationRequest(
                "CC-IDEM", "C-101", "Z-A1", W0, W1, new BigDecimal("1000"));
        List<Future<AuthOutcome>> futures = runParallel(threads, idx -> {
            try {
                AuthorizationView v = authorizationService.apply(shared);
                return new AuthOutcome("GRANTED".equals(v.result()), false, false);
            } catch (ConflictException c) {
                return new AuthOutcome(false, true, false);
            }
        });

        for (Future<AuthOutcome> f : futures) {
            AuthOutcome o = f.get();
            assertThat(o.granted()).isTrue();
            assertThat(o.conflict()).isFalse();
        }
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(1) FROM zone_authorization WHERE request_id = 'CC-IDEM'",
                Integer.class)).isEqualTo(1);
    }

    @Test
    void concurrent_identical_pose_only_first_generates_warning() throws Exception {
        int threads = 8;
        PoseRequest sharedPose = new PoseRequest("C-101", W0, "Z-A1", null, null, null);
        List<Future<PoseAcceptance>> futures = runParallel(threads, idx ->
                poseProcessingService.ingest(sharedPose));

        int fresh = 0;
        int duplicate = 0;
        for (Future<PoseAcceptance> f : futures) {
            PoseAcceptance a = f.get();
            if (a.duplicated()) {
                duplicate++;
            } else {
                fresh++;
            }
        }
        assertThat(fresh).isEqualTo(1);
        assertThat(duplicate).isEqualTo(threads - 1);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(1) FROM pose_reading WHERE crane_code = 'C-101' AND ts = ?",
                Integer.class, java.sql.Timestamp.from(W0))).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(1) FROM intrusion_warning WHERE crane_code = 'C-101'",
                Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(1) FROM brake_command WHERE crane_code = 'C-101'",
                Integer.class)).isZero();
    }

    @Test
    void release_and_pose_ingest_concurrent_produce_single_legal_conclusion() throws Exception {
        authorizationService.apply(new AuthorizationRequest(
                "CC-RACE", "C-101", "Z-A1", W0, W1, new BigDecimal("1000")));

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch ready = new CountDownLatch(2);
            CountDownLatch start = new CountDownLatch(1);

            // 位姿线程：在授权窗口内连续上报 8 个不同采样点（作业临近结束）。
            Future<Integer> poseFuture = pool.submit(() -> {
                ready.countDown();
                start.await(10, TimeUnit.SECONDS);
                int accepted = 0;
                for (int sec = 1; sec <= 8; sec++) {
                    // 微小交错，使释放可能发生在任意两次上报之间。
                    Thread.sleep(sec % 3);
                    PoseAcceptance a = poseProcessingService.ingest(
                            new PoseRequest("C-101", W0.plusSeconds(sec), "Z-A1", null, null, null));
                    if (!a.duplicated()) {
                        accepted++;
                    }
                }
                return accepted;
            });
            // 释放线程：作业完成。
            Future<?> releaseFuture = pool.submit(() -> {
                ready.countDown();
                start.await(10, TimeUnit.SECONDS);
                Thread.sleep(4);
                authorizationService.release("CC-RACE", new ReleaseRequest("COMPLETED"));
                return null;
            });

            start.countDown();
            assertThat(poseFuture.get(30, TimeUnit.SECONDS)).isEqualTo(8);
            releaseFuture.get(30, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
            pool.awaitTermination(10, TimeUnit.SECONDS);
        }

        // 无论释放先提交还是后提交，完成释放口径下窗口内历史作业均合法：唯一结论是零越限、零制动。
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(1) FROM intrusion_warning WHERE crane_code = 'C-101'",
                Integer.class)).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(1) FROM brake_command WHERE crane_code = 'C-101'",
                Integer.class)).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(1) FROM pose_reading WHERE crane_code = 'C-101'",
                Integer.class)).isEqualTo(8);
    }
}
