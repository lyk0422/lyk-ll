package com.example.starter.orchestrator;

import com.example.starter.orchestrator.api.PlanResponse;
import com.example.starter.orchestrator.api.SubmitPlanRequest;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static com.example.starter.orchestrator.PlanTestFactory.node;
import static com.example.starter.orchestrator.PlanTestFactory.plan;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 幂等边界：同 requestId 同参重复提交幂等；异参提交 409；planId 冲突 409。
 */
class PlanIdempotencyH2Test extends AbstractOrchestratorH2Test {

    private SubmitPlanRequest baseRequest(String requestId, long delay) {
        return new SubmitPlanRequest("p-idem", "v1", requestId, "idem-plan", List.of(
                new SubmitPlanRequest.NodeSpec("db", "db", delay, false,
                        java.util.Map.of(), List.of(), null, null, null)));
    }

    @Test
    void sameRequestIdSameParametersIsIdempotent() {
        SubmitPlanRequest first = baseRequest("req-idem-1", 0L);
        PlanResponse r1 = service.submit(first);
        PlanResponse r2 = service.submit(baseRequest("req-idem-1", 0L));

        assertThat(r1.status()).isEqualTo("SUCCESS");
        assertThat(r2.planId()).isEqualTo(r1.planId());
        assertThat(r2.status()).isEqualTo(r1.status());
        // 只发生过一次执行：NODE_STARTED 事件仅一条
        long startedCount = repo.findEventsByPlanId("p-idem").stream()
                .filter(e -> "NODE_STARTED".equals(e.eventType())).count();
        assertThat(startedCount).isEqualTo(1);
        // PLAN_SUBMITTED 也只记录一次
        long submittedCount = repo.findEventsByPlanId("p-idem").stream()
                .filter(e -> "PLAN_SUBMITTED".equals(e.eventType())).count();
        assertThat(submittedCount).isEqualTo(1);
    }

    @Test
    void sameRequestIdDifferentParametersReturns409() {
        service.submit(baseRequest("req-idem-2", 0L));

        // 同 requestId，但节点延迟参数不同（异参）
        assertThatThrownBy(() -> service.submit(baseRequest("req-idem-2", 50L)))
                .isInstanceOf(IdempotencyConflictException.class)
                .hasMessageContaining("different parameters");

        // 原计划结果不受影响
        PlanResponse current = service.getPlan("p-idem");
        assertThat(current.status()).isEqualTo("SUCCESS");
    }

    @Test
    void sameRequestIdDifferentPlanIdReturns409() {
        service.submit(plan("p-idem-a", "v1", "req-cross", List.of(node("a"))));
        assertThatThrownBy(() -> service.submit(plan("p-idem-b", "v1", "req-cross", List.of(node("a")))))
                .isInstanceOf(IdempotencyConflictException.class);
    }

    @Test
    void duplicatePlanIdWithNewRequestIdReturns409() {
        service.submit(plan("p-dup-id", "v1", "req-a", List.of(node("a"))));
        assertThatThrownBy(() -> service.submit(plan("p-dup-id", "v1", "req-b", List.of(node("a")))))
                .isInstanceOf(IdempotencyConflictException.class)
                .hasMessageContaining("planId already exists");
    }

    @Test
    void differentRequestsAreIndependentPlans() {
        service.submit(plan("p-x", "v1", "req-x", List.of(node("a"))));
        PlanResponse y = service.submit(plan("p-y", "v1", "req-y", List.of(node("b"))));
        assertThat(y.status()).isEqualTo("SUCCESS");
        assertThat(repo.findPlanByPlanId("p-x")).isNotNull();
        assertThat(repo.findPlanByPlanId("p-y")).isNotNull();
    }

    @Test
    void concurrentSameRequestIdSubmissionsCreateSinglePlanAndSingleLaunch() throws Exception {
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<PlanResponse>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return service.submit(baseRequest("req-concurrent", 0L));
                }));
            }
            start.countDown();

            int returned = 0;
            int conflicts = 0;
            for (Future<PlanResponse> f : futures) {
                try {
                    f.get(10, TimeUnit.SECONDS);
                    returned++;
                } catch (Exception e) {
                    Throwable cause = e.getCause() != null ? e.getCause() : e;
                    assertThat(cause).isInstanceOf(IdempotencyConflictException.class);
                    conflicts++;
                }
            }

            // 唯一约束 + 幂等判定下：恰好一个请求落库（其余返回既有结果或冲突）
            assertThat(returned + conflicts).isEqualTo(threads);
            assertThat(returned).isGreaterThanOrEqualTo(1);

            // 等待执行结束（同步执行器下首个落库请求会执行到 SUCCESS）
            Instant deadline = Instant.now().plus(Duration.ofSeconds(10));
            while (Instant.now().isBefore(deadline)
                    && !"SUCCESS".equals(service.getPlan("p-idem").status())) {
                Thread.sleep(20);
            }
            assertThat(service.getPlan("p-idem").status()).isEqualTo("SUCCESS");

            // 节点只被启动一次：plan_node 只有一行，NODE_STARTED 事件只有一条
            assertThat(repo.findNodesByPlanId("p-idem")).hasSize(1);
            long startedCount = repo.findEventsByPlanId("p-idem").stream()
                    .filter(e -> "NODE_STARTED".equals(e.eventType())).count();
            long submittedCount = repo.findEventsByPlanId("p-idem").stream()
                    .filter(e -> "PLAN_SUBMITTED".equals(e.eventType())).count();
            assertThat(startedCount).isEqualTo(1);
            assertThat(submittedCount).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }
}
