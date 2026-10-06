package com.example.starter.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import com.example.starter.TestClockConfig;
import com.example.starter.web.ApiException;
import com.example.starter.web.dto.PlanResponse;
import com.example.starter.web.dto.Requests.ApplyRequest;
import com.example.starter.web.dto.Requests.ConfirmRequest;
import com.example.starter.web.dto.Requests.ScheduleRequest;

/**
 * 并发边界测试（真实线程 + H2 行锁/唯一约束）：
 * 同一拖轮的并发确认仅一个成功；同 requestId 并发写幂等重放。
 * 全部并发操作带超时断言，最终以数据库状态为准。
 */
@SpringBootTest
@Import(TestClockConfig.class)
class PilotPlanConcurrencyTest {

    private static final LocalDateTime DAY =
            TestClockConfig.BASE_TIME.toLocalDate().plusDays(1).atTime(0, 0);

    @Autowired
    private PilotPlanService service;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM idempotency_record");
        jdbc.update("DELETE FROM violation_record");
        jdbc.update("DELETE FROM resource_lock");
        jdbc.update("DELETE FROM plan_tug");
        jdbc.update("DELETE FROM pilot_plan");
        TestClockConfig.mutableClock().setNow(TestClockConfig.BASE_TIME);
    }

    @Test
    @DisplayName("并发确认同一拖轮同一时段：恰有一个成功，失败者无残留锁")
    void concurrentConfirmSameTugExactlyOneWins() throws Exception {
        long planA = service.apply(new ApplyRequest(
                "CC-applyA", "测试船代", "甲轮", DAY.plusHours(8), DAY.plusHours(12))).planId();
        service.schedule(new ScheduleRequest("CC-schedA", planA, 1L, List.of(1L),
                DAY.plusHours(8), DAY.plusHours(12)));
        long planB = service.apply(new ApplyRequest(
                "CC-applyB", "测试船代", "乙轮", DAY.plusHours(8), DAY.plusHours(12))).planId();
        service.schedule(new ScheduleRequest("CC-schedB", planB, 2L, List.of(1L),
                DAY.plusHours(8), DAY.plusHours(12)));

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        AtomicInteger success = new AtomicInteger();
        AtomicInteger conflict = new AtomicInteger();
        try {
            Future<?> f1 = pool.submit(() -> confirmOnce(planA, "CC-confirmA", ready, go, success, conflict));
            Future<?> f2 = pool.submit(() -> confirmOnce(planB, "CC-confirmB", ready, go, success, conflict));
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            go.countDown();
            f1.get(30, TimeUnit.SECONDS);
            f2.get(30, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }

        assertThat(success.get()).isEqualTo(1);
        assertThat(conflict.get()).isEqualTo(1);
        // 拖轮 1 最终只被一条计划锁定
        Integer tugLocks = jdbc.queryForObject(
                "SELECT COUNT(*) FROM resource_lock WHERE resource_type = 'TUG' AND resource_id = 1",
                Integer.class);
        assertThat(tugLocks).isEqualTo(1);
    }

    private void confirmOnce(long planId, String requestId, CountDownLatch ready, CountDownLatch go,
                             AtomicInteger success, AtomicInteger conflict) {
        try {
            ready.countDown();
            go.await(10, TimeUnit.SECONDS);
            service.confirm(new ConfirmRequest(requestId, planId));
            success.incrementAndGet();
        } catch (ApiException e) {
            conflict.incrementAndGet();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    @DisplayName("同 requestId 同参数并发申请：全部返回首次结果，仅落库一次")
    void concurrentSameRequestIdApplyIsIdempotent() throws Exception {
        int threads = 4;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        ApplyRequest request = new ApplyRequest(
                "CI-same", "测试船代", "远洋轮", DAY.plusHours(8), DAY.plusHours(10));
        try {
            List<Future<PlanResponse>> futures = java.util.stream.IntStream.range(0, threads)
                    .mapToObj(i -> pool.submit(() -> {
                        ready.countDown();
                        go.await(10, TimeUnit.SECONDS);
                        return service.apply(request);
                    }))
                    .toList();
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            go.countDown();
            long firstPlanId = -1;
            for (Future<PlanResponse> future : futures) {
                PlanResponse response = future.get(30, TimeUnit.SECONDS);
                if (firstPlanId < 0) {
                    firstPlanId = response.planId();
                }
                assertThat(response.planId()).isEqualTo(firstPlanId);
            }
        } finally {
            pool.shutdownNow();
        }

        Integer planCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM pilot_plan WHERE request_id = 'CI-same'", Integer.class);
        Integer idemCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM idempotency_record WHERE request_id = 'CI-same'", Integer.class);
        assertThat(planCount).isEqualTo(1);
        assertThat(idemCount).isEqualTo(1);
    }
}
