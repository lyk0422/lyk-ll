package com.example.starter.business;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;

import com.example.starter.AbstractIntegrationTest;
import com.example.starter.api.dto.ExecutePlanRequest;
import com.example.starter.api.dto.PlanResultResponse;
import com.example.starter.api.dto.PlanSubmitRequest;
import com.example.starter.api.dto.PlanStatusResponse;
import com.example.starter.api.dto.SwitchPlanRequest;
import com.example.starter.service.CementService;
import com.example.starter.support.TestFixtures;

/**
 * H2 真实并发与事务边界：并发申报唯一受理、切换两步同一事务（失败回滚不停旧）。
 */
class ConcurrencyTransactionTest extends AbstractIntegrationTest {

    @Autowired
    private CementService service;

    @Autowired
    private PlatformTransactionManager txManager;

    private static final LocalDate DAY = LocalDate.of(2026, 9, 25);

    private PlanSubmitRequest overlappingRequest(String requestId) {
        return TestFixtures.submit(requestId, KILN, VALID_LICENSE, "0.4000",
                DAY.atTime(0, 0), DAY.atTime(23, 59),
                List.of(TestFixtures.normalFuel()));
    }

    @Test
    void concurrent_submissions_for_same_kiln_accept_only_one() throws Exception {
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger accepted = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();
        List<Future<?>> futures = new java.util.ArrayList<>();

        for (int i = 0; i < threads; i++) {
            String reqId = String.format("REQ-CONC-%03d", i);
            futures.add(pool.submit(() -> {
                ready.countDown();
                start.await();
                PlanResultResponse resp = service.submit(overlappingRequest(reqId));
                if (resp.accepted()) {
                    accepted.incrementAndGet();
                } else {
                    rejected.incrementAndGet();
                }
                return null;
            }));
        }
        assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
        start.countDown();
        for (Future<?> f : futures) {
            f.get(30, TimeUnit.SECONDS);
        }
        pool.shutdown();

        assertThat(accepted.get()).isEqualTo(1);
        assertThat(rejected.get()).isEqualTo(threads - 1);
        Integer approved = jdbc.queryForObject(
                "SELECT COUNT(*) FROM co_firing_plan WHERE status = 'APPROVED'",
                Integer.class);
        Integer rejectedRows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM co_firing_plan WHERE status = 'REJECTED' AND reject_category = 'KILN_OCCUPIED'",
                Integer.class);
        assertThat(approved).isEqualTo(1);
        assertThat(rejectedRows).isEqualTo(threads - 1);
    }

    @Test
    void concurrent_execute_only_one_occupies_kiln() throws Exception {
        // 先准备多个审核通过、时段重叠的方案需绕过申报重叠校验：直接构造不同计划窗口再并发执行
        PlanResultResponse first = service.submit(TestFixtures.submit("REQ-EXE-1", KILN,
                VALID_LICENSE, "0.4000", DAY.atTime(0, 0), DAY.atTime(23, 59),
                List.of(TestFixtures.normalFuel())));
        // 第二个人工改窗审核通过（模拟历史已批准同刻方案）
        PlanResultResponse second = service.submit(TestFixtures.submit("REQ-EXE-2", KILN,
                VALID_LICENSE, "0.3000", DAY.atTime(0, 0), DAY.atTime(8, 0),
                List.of(TestFixtures.normalFuel())));
        // 第二条因重叠被拒绝：手工将其置为 APPROVED 以验证执行槽唯一约束
        jdbc.update("UPDATE co_firing_plan SET status = 'APPROVED', reject_category = NULL, "
                + "reject_reason = NULL WHERE request_id = 'REQ-EXE-2'");

        int threads = 4;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger success = new AtomicInteger();
        AtomicInteger conflict = new AtomicInteger();
        List<Future<?>> futures = new java.util.ArrayList<>();
        for (int i = 0; i < threads; i++) {
            String planNo = i % 2 == 0 ? first.planNo() : second.planNo();
            futures.add(pool.submit(() -> {
                start.await();
                try {
                    service.execute(new ExecutePlanRequest(planNo));
                    success.incrementAndGet();
                } catch (RuntimeException e) {
                    conflict.incrementAndGet();
                }
                return null;
            }));
        }
        start.countDown();
        for (Future<?> f : futures) {
            f.get(30, TimeUnit.SECONDS);
        }
        pool.shutdown();

        assertThat(success.get()).isEqualTo(1);
        assertThat(conflict.get()).isEqualTo(threads - 1);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM kiln_active_plan WHERE kiln_id = ?",
                Integer.class, KILN)).isEqualTo(1);
    }

    @Test
    void switch_is_atomic_when_new_plan_cannot_start_old_keeps_running() {
        PlanResultResponse p1 = service.submit(TestFixtures.submit("REQ-ATOM-A", KILN,
                VALID_LICENSE, "0.4000", DAY.atTime(0, 0), DAY.atTime(12, 0),
                List.of(TestFixtures.normalFuel())));
        service.execute(new ExecutePlanRequest(p1.planNo()));
        PlanResultResponse p2 = service.submit(TestFixtures.submit("REQ-ATOM-B", KILN,
                VALID_LICENSE, "0.2000", DAY.atTime(12, 0), DAY.atTime(23, 59),
                List.of(TestFixtures.normalFuel())));
        // 制造“启新”阶段的真实数据库失败：新 plan_id 已占用另一窑的执行槽，
        // 切换事务把窑占用行改挂新 plan_id 时触发 uk_active_plan_id 唯一约束
        jdbc.update("INSERT INTO kiln_active_plan (kiln_id, plan_id, acquired_at) VALUES (?, ?, ?)",
                "KILN-OTHER", p2.planId(), java.sql.Timestamp.valueOf(DAY.atTime(11, 0)));

        try {
            service.switchPlan(new SwitchPlanRequest("REQ-ATOM-SW", KILN, p2.planNo()));
            throw new AssertionError("切换应失败");
        } catch (RuntimeException expected) {
            // 唯一约束异常被同一事务回滚
        }

        PlanStatusResponse old = service.status(p1.planNo());
        assertThat(old.status()).isEqualTo("EXECUTING");
        Long activePlanId = jdbc.queryForObject(
                "SELECT plan_id FROM kiln_active_plan WHERE kiln_id = ?", Long.class, KILN);
        assertThat(activePlanId).isEqualTo(p1.planId());
    }

    @Test
    void transaction_manager_bean_is_available() {
        assertThat(txManager).isNotNull();
    }
}
