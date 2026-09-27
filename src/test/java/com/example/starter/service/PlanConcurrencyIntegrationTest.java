package com.example.starter.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import com.example.starter.AbstractIntegrationTest;
import com.example.starter.dto.OrderReceiveResponse;
import com.example.starter.dto.PlanBindingEvidenceResponse;
import com.example.starter.dto.PlanOrderReceiveRequest;
import com.example.starter.dto.PlanPublishRequest;
import com.example.starter.dto.PlanPublishResponse;
import com.example.starter.web.ApiConflictException;
import com.example.starter.web.ApiUnprocessableException;

/**
 * 计划发布与绑定指令 H2 真实并发测试：风场行锁串行化发布与受理，
 * 版本序列唯一、同键并发唯一受理、发布与受理竞争时版本与基数一致。
 */
@SpringBootTest
class PlanConcurrencyIntegrationTest extends AbstractIntegrationTest {

    private static final LocalDate DATE = LocalDate.of(2026, 9, 27);

    @Autowired
    private PlanService planService;

    @Autowired
    private DispatchService dispatchService;

    @BeforeEach
    void setUp() {
        resetDb();
        pinClock(10, 0, 0);
    }

    private PlanPublishRequest uniformPlan(String requestId, int expectedVersion, int value) {
        List<PlanPublishRequest.SlotPlan> slots = new ArrayList<>(96);
        for (int s = 0; s < 96; s++) {
            slots.add(new PlanPublishRequest.SlotPlan(s, List.of(
                    new PlanPublishRequest.TurbinePlan("T-01", value),
                    new PlanPublishRequest.TurbinePlan("T-02", value),
                    new PlanPublishRequest.TurbinePlan("T-03", Math.min(value, 3000)))));
        }
        return new PlanPublishRequest(requestId, FARM, DATE, expectedVersion, slots);
    }

    @Test
    @DisplayName("同场同日并发首发（同 expectedVersion=0）：仅一个成功，版本序列无空洞")
    void concurrentFirstPublishOnlyOneWins() throws Exception {
        int threads = 6;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            final int idx = i;
            futures.add(pool.submit(() -> {
                start.await();
                return planService.publish(uniformPlan("plan-conc-" + idx, 0, 1000));
            }));
        }
        start.countDown();

        int success = 0;
        int conflict = 0;
        for (Future<?> f : futures) {
            try {
                f.get(30, TimeUnit.SECONDS);
                success++;
            } catch (Exception ex) {
                assertThat(ex.getCause()).isInstanceOf(ApiConflictException.class);
                conflict++;
            }
        }
        pool.shutdown();

        assertThat(success).isEqualTo(1);
        assertThat(conflict).isEqualTo(threads - 1);
        assertThat(planService.versions(FARM, DATE)).hasSize(1);
        assertThat(planService.current(FARM, DATE).version()).isEqualTo(1);
        Integer currentCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM day_ahead_plan WHERE current_flag = 'Y'", Integer.class);
        assertThat(currentCount).isEqualTo(1);
    }

    @Test
    @DisplayName("同一 requestId 并发发布：唯一受理，其余重放首次结果")
    void concurrentSameRequestIdPublishedOnce() throws Exception {
        int threads = 6;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<PlanPublishResponse>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                start.await();
                return planService.publish(uniformPlan("plan-same-key", 0, 1000));
            }));
        }
        start.countDown();

        List<PlanPublishResponse> results = new ArrayList<>();
        for (Future<PlanPublishResponse> f : futures) {
            results.add(f.get(30, TimeUnit.SECONDS));
        }
        pool.shutdown();

        assertThat(results.stream().map(PlanPublishResponse::planId).distinct().count())
                .isEqualTo(1);
        assertThat(results.stream().filter(r -> !r.replayed()).count()).isEqualTo(1);
        assertThat(results.stream().filter(PlanPublishResponse::replayed).count())
                .isEqualTo(threads - 1);
        assertThat(planService.versions(FARM, DATE)).hasSize(1);
        Integer idemCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM idempotent_record WHERE request_id = 'plan-same-key'",
                Integer.class);
        assertThat(idemCount).isEqualTo(1);
    }

    @Test
    @DisplayName("发布与绑定受理竞争：受理校验的版本与冻结基数必属同一版本")
    void publishAndBindRaceKeepsVersionAndBasesConsistent() throws Exception {
        // v1 时段 0 核定 1500/1200/2000。
        List<PlanPublishRequest.SlotPlan> slots = new ArrayList<>(96);
        for (int s = 0; s < 96; s++) {
            slots.add(new PlanPublishRequest.SlotPlan(s, List.of(
                    new PlanPublishRequest.TurbinePlan("T-01", s == 0 ? 1500 : 1000),
                    new PlanPublishRequest.TurbinePlan("T-02", s == 0 ? 1200 : 1000),
                    new PlanPublishRequest.TurbinePlan("T-03", s == 0 ? 2000 : 2000))));
        }
        planService.publish(new PlanPublishRequest("plan-race-v1", FARM, DATE, 0, slots));

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        // 线程 A：发布 v2（时段 0 全 500）。
        Future<PlanPublishResponse> publishFuture = pool.submit(() -> {
            start.await();
            List<PlanPublishRequest.SlotPlan> v2Slots = new ArrayList<>(96);
            for (int s = 0; s < 96; s++) {
                v2Slots.add(new PlanPublishRequest.SlotPlan(s, List.of(
                        new PlanPublishRequest.TurbinePlan("T-01", 500),
                        new PlanPublishRequest.TurbinePlan("T-02", 500),
                        new PlanPublishRequest.TurbinePlan("T-03", 500))));
            }
            return planService.publish(
                    new PlanPublishRequest("plan-race-v2", FARM, DATE, 1, v2Slots));
        });
        // 线程 B：绑定 v1 时段 0。
        Future<OrderReceiveResponse> bindFuture = pool.submit(() -> {
            start.await();
            return dispatchService.receivePlanBound(
                    new PlanOrderReceiveRequest("bind-race", FARM, 4000, DATE, 0, 1));
        });
        start.countDown();

        PlanPublishResponse v2 = publishFuture.get(30, TimeUnit.SECONDS);
        OrderReceiveResponse bound = null;
        boolean bindRejected = false;
        try {
            bound = bindFuture.get(30, TimeUnit.SECONDS);
        } catch (Exception ex) {
            // 发布先完成时，v1 已过期，绑定被 422 拒绝。
            assertThat(ex.getCause()).isInstanceOf(ApiUnprocessableException.class);
            bindRejected = true;
        }
        pool.shutdown();

        // 发布总是成功：v2 成为当前版本，历史保留。
        assertThat(v2.version()).isEqualTo(2);
        assertThat(planService.current(FARM, DATE).version()).isEqualTo(2);
        assertThat(planService.versions(FARM, DATE)).hasSize(2);

        if (bindRejected) {
            // 绑定未受理：不存在指令与冻结基数。
            Integer orderCount = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM curtail_order", Integer.class);
            assertThat(orderCount).isZero();
        } else {
            // 绑定受理成功：其冻结版本与基数必为校验时的当前版本 v1（1500/1200/2000），
            // 绝不混入 v2 的 500。
            PlanBindingEvidenceResponse evidence =
                    dispatchService.planBindingEvidence(bound.orderNo());
            assertThat(evidence.planVersion()).isEqualTo(1);
            assertThat(evidence.bases()).allSatisfy(b -> assertThat(b.plannedKw()).isNotEqualTo(500));
            var byCode = evidence.bases().stream()
                    .collect(java.util.stream.Collectors.toMap(b -> b.turbineCode(), b -> b));
            assertThat(byCode.get("T-01").plannedKw()).isEqualTo(1500);
            assertThat(byCode.get("T-02").plannedKw()).isEqualTo(1200);
            assertThat(byCode.get("T-03").plannedKw()).isEqualTo(2000);
        }
    }

    @Test
    @DisplayName("同风场同时刻并发绑定指令：仅一个受理，其余 409")
    void concurrentBoundOrdersSameMomentOnlyOneAccepted() throws Exception {
        planService.publish(uniformPlan("plan-bind-conc", 0, 1500));

        int threads = 6;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            final int idx = i;
            futures.add(pool.submit(() -> {
                start.await();
                return dispatchService.receivePlanBound(
                        new PlanOrderReceiveRequest("bind-conc-" + idx, FARM, 3000, DATE, 0, 1));
            }));
        }
        start.countDown();

        int success = 0;
        int conflict = 0;
        for (Future<?> f : futures) {
            try {
                f.get(30, TimeUnit.SECONDS);
                success++;
            } catch (Exception ex) {
                assertThat(ex.getCause()).isInstanceOf(ApiConflictException.class);
                conflict++;
            }
        }
        pool.shutdown();

        assertThat(success).isEqualTo(1);
        assertThat(conflict).isEqualTo(threads - 1);
        Integer activeCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM curtail_order WHERE active_flag = 'Y'", Integer.class);
        assertThat(activeCount).isEqualTo(1);
        Integer baseCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM order_plan_base", Integer.class);
        assertThat(baseCount).isEqualTo(3);
    }
}
