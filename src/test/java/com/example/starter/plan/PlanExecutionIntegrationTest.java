package com.example.starter.plan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.starter.AbstractIntegrationTest;
import com.example.starter.api.dto.DeclarePlanRequest;
import com.example.starter.api.dto.PlanResponse;
import com.example.starter.api.dto.ReviewDecision;
import com.example.starter.api.dto.ReviewRequest;
import com.example.starter.core.ApiException;
import com.example.starter.core.IdempotencyService;
import com.example.starter.domain.PlanStatus;
import com.example.starter.support.TestFixtures;
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

/**
 * 方案执行：同窑同时刻唯一执行槽、切换先停旧后启新原子性、并发申报唯一受理。
 */
class PlanExecutionIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private IdempotencyService idempotencyService;

    private void seedValidLicense() {
        licenseService.upsert(TestFixtures.license(TestFixtures.LICENSE,
                LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31)));
    }

    private Long approvePlan(String requestId, String kilnId) {
        PlanResponse declared = planService.declare(TestFixtures.declareRequest(requestId, kilnId, "0.4000"));
        return planService.review(declared.id(), new ReviewRequest(ReviewDecision.APPROVE, null)).id();
    }

    @Test
    void switchingPlanStopsOldAndStartsNewAtomically() {
        seedValidLicense();
        Long firstId = approvePlan("req-switch-1", "KILN-S");
        PlanResponse firstExecuting = planService.execute(firstId);
        assertThat(firstExecuting.status()).isEqualTo(PlanStatus.EXECUTING.name());

        Long secondId = approvePlan("req-switch-2", "KILN-S");
        PlanResponse secondExecuting = planService.execute(secondId);

        assertThat(secondExecuting.status()).isEqualTo(PlanStatus.EXECUTING.name());
        assertThat(planService.get(firstId).status()).isEqualTo(PlanStatus.STOPPED.name());
        // 窑口槽只指向新方案，旧方案不再占用。
        Integer slotCount = jdbc.queryForObject(
                "SELECT COUNT(1) FROM kiln_execution_slot WHERE kiln_id = 'KILN-S'", Integer.class);
        assertThat(slotCount).isEqualTo(1);
        Long occupant = jdbc.queryForObject(
                "SELECT plan_id FROM kiln_execution_slot WHERE kiln_id = 'KILN-S'", Long.class);
        assertThat(occupant).isEqualTo(secondId);
    }

    @Test
    void twoDifferentKilnsCanExecuteConcurrently() {
        seedValidLicense();
        Long kilnAPlan = approvePlan("req-ka", "KILN-A");
        Long kilnBPlan = approvePlan("req-kb", "KILN-B");
        assertThat(planService.execute(kilnAPlan).status()).isEqualTo(PlanStatus.EXECUTING.name());
        assertThat(planService.execute(kilnBPlan).status()).isEqualTo(PlanStatus.EXECUTING.name());
    }

    @Test
    void bannedKilnCannotExecuteNewPlanThatDay() {
        seedValidLicense();
        Long planId = approvePlan("req-ban-exec", "KILN-BAN");
        planService.execute(planId);
        // 手工构造当日禁烧标记：新方案审核通过后执行应被拒绝。
        jdbc.update("INSERT INTO daily_ban (kiln_id, ban_date, triggered_plan_id, created_at) "
                + "VALUES ('KILN-BAN', ?, ?, CURRENT_TIMESTAMP)",
                LocalDate.of(2026, 9, 25), planId);
        Long otherId = approvePlan("req-ban-exec-2", "KILN-BAN");
        assertThatThrownBy(() -> planService.execute(otherId))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("禁烧");
        assertThat(planService.get(otherId).status()).isEqualTo(PlanStatus.APPROVED.name());
    }

    @Test
    void concurrentDeclareWithSameRequestIdAcceptedExactlyOnce() throws Exception {
        seedValidLicense();
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger firstAccepted = new AtomicInteger();
        AtomicInteger replayed = new AtomicInteger();
        AtomicInteger errors = new AtomicInteger();
        java.util.concurrent.ConcurrentLinkedQueue<String> errorMessages =
                new java.util.concurrent.ConcurrentLinkedQueue<>();
        List<Future<?>> futures = new java.util.ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                ready.countDown();
                start.await();
                try {
                    DeclarePlanRequest request = TestFixtures.declareRequest(
                            "req-concurrent-1", "KILN-C", "0.4000");
                    var envelope = idempotencyService.execute(
                            "DECLARE_PLAN", request.requestId(),
                            "same-fingerprint", PlanResponse.class,
                            () -> {
                                firstAccepted.incrementAndGet();
                                return planService.declare(request);
                            });
                    if (envelope.replayed()) {
                        replayed.incrementAndGet();
                    }
                } catch (Exception e) {
                    errors.incrementAndGet();
                    errorMessages.add(e.getClass().getSimpleName() + ": " + e.getMessage());
                }
                return null;
            }));
        }
        ready.await(5, TimeUnit.SECONDS);
        start.countDown();
        for (Future<?> future : futures) {
            future.get(10, TimeUnit.SECONDS);
        }
        pool.shutdown();

        org.assertj.core.api.Assertions.assertThat(errors.get())
                .as("并发线程异常：%s", errorMessages)
                .isZero();
        assertThat(firstAccepted.get())
                .as("唯一受理计数（异常：%s）", errorMessages)
                .isEqualTo(1);
        assertThat(firstAccepted.get() + replayed.get()).isEqualTo(threads);
        Integer planCount = jdbc.queryForObject(
                "SELECT COUNT(1) FROM co_firing_plan WHERE declare_request_id = 'req-concurrent-1'",
                Integer.class);
        assertThat(planCount).isEqualTo(1);
    }
}
