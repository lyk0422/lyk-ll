package com.example.starter.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
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

import com.example.starter.AbstractIntegrationTest;
import com.example.starter.dto.OrderBasisResponse;
import com.example.starter.dto.PlanOrderReceiveRequest;
import com.example.starter.dto.PlanOrderReceiveResponse;
import com.example.starter.dto.PlanPublishRequest;
import com.example.starter.web.ApiConflictException;
import com.example.starter.web.ApiUnprocessableException;

/**
 * H2 真实并发测试：计划发布同键唯一受理、不同键首版竞争只有一个 v1、
 * 发布与计划绑定指令受理竞争时版本与冻结基数始终一致、绑定同键唯一受理。
 */
@SpringBootTest
class PlanDispatchConcurrencyIntegrationTest extends AbstractIntegrationTest {

    private static final int SEGMENT = 40;

    @Autowired
    private PlanService planService;

    @Autowired
    private DispatchService dispatchService;

    @BeforeEach
    void setUp() {
        resetDb();
        pinClock(10, 0, 0);
    }

    @Test
    @DisplayName("同一 requestId 并发发布计划：唯一受理，其余全部重放首次冻结结果")
    void sameRequestIdConcurrentPublishOnce() throws Exception {
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<com.example.starter.dto.PlanPublishResponse>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            PlanPublishRequest req = new PlanPublishRequest("same-plan-key", FARM, PLAN_DATE, 1,
                    localInstant(9, 0, 0), fullSegments(1000));
            futures.add(pool.submit(() -> {
                start.await();
                return planService.publish(req);
            }));
        }
        start.countDown();

        List<com.example.starter.dto.PlanPublishResponse> results = new ArrayList<>();
        for (Future<com.example.starter.dto.PlanPublishResponse> f : futures) {
            results.add(f.get(30, TimeUnit.SECONDS));
        }
        pool.shutdown();

        long distinct = results.stream()
                .map(com.example.starter.dto.PlanPublishResponse::versionId).distinct().count();
        assertThat(distinct).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM day_ahead_plan_version", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM day_ahead_plan_segment", Integer.class)).isEqualTo(288);
    }

    @Test
    @DisplayName("不同 requestId 同 expectedVersion=1 并发首发：恰好一个成功，其余 409")
    void concurrentFirstPublishOnlyOneSucceeds() throws Exception {
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            final int idx = i;
            PlanPublishRequest req = new PlanPublishRequest("first-" + idx, FARM, PLAN_DATE, 1,
                    localInstant(9, 0, idx), fullSegments(1000 + idx));
            futures.add(pool.submit(() -> {
                start.await();
                return planService.publish(req);
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
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM day_ahead_plan_version", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM day_ahead_plan_version WHERE current_flag='Y'",
                Integer.class)).isEqualTo(1);
    }

    @Test
    @DisplayName("发布 v2 与绑定 v1 并发竞争：受理则基数必为 v1 冻结值，否则 422，绝不绑定非当前版本")
    void publishVersusBindRaceKeepsVersionAndBasisConsistent() throws Exception {
        // 多轮竞争（每轮清库），两种先后次序都可能出现，但不变量必须每轮成立。
        for (int round = 0; round < 6; round++) {
            resetDb();
            pinClock(10, 0, 0);
            planService.publish(new PlanPublishRequest("v1-" + round, FARM, PLAN_DATE, 1,
                    localInstant(9, 0, 0), fullSegments(1500)));

            PlanPublishRequest publishV2 = new PlanPublishRequest("v2-" + round, FARM, PLAN_DATE, 2,
                    localInstant(10, 5, 0), fullSegments(800));
            PlanOrderReceiveRequest bindV1 = new PlanOrderReceiveRequest(
                    "bind-" + round, FARM, 3000, PLAN_DATE, SEGMENT, 1, localInstant(10, 5, 0));

            ExecutorService pool = Executors.newFixedThreadPool(2);
            CountDownLatch ready = new CountDownLatch(2);
            CountDownLatch go = new CountDownLatch(1);
            AtomicInteger published = new AtomicInteger();
            AtomicInteger bound = new AtomicInteger();
            AtomicInteger expired = new AtomicInteger();
            List<Throwable> other = new ArrayList<>();
            List<PlanOrderReceiveResponse> boundResponses = new ArrayList<>();

            Future<?> pf = pool.submit(() -> {
                ready.countDown();
                go.await();
                try {
                    planService.publish(publishV2);
                    published.incrementAndGet();
                } catch (Throwable t) {
                    synchronized (other) {
                        other.add(t);
                    }
                }
                return null;
            });
            Future<?> bf = pool.submit(() -> {
                ready.countDown();
                go.await();
                try {
                    boundResponses.add(dispatchService.receivePlanOrder(bindV1));
                    bound.incrementAndGet();
                } catch (ApiUnprocessableException ex) {
                    expired.incrementAndGet();
                } catch (Throwable t) {
                    synchronized (other) {
                        other.add(t);
                    }
                }
                return null;
            });
            ready.await(30, TimeUnit.SECONDS);
            go.countDown();
            pf.get(30, TimeUnit.SECONDS);
            bf.get(30, TimeUnit.SECONDS);
            pool.shutdown();

            assertThat(other).isEmpty();
            assertThat(published.get()).isEqualTo(1);
            // 绑定要么成功（先于发布），要么被 422 拒绝（晚于发布），不存在第三种结果。
            assertThat(bound.get() + expired.get()).isEqualTo(1);
            assertThat(jdbc.queryForObject(
                    "SELECT COUNT(*) FROM day_ahead_plan_version WHERE current_flag='Y'",
                    Integer.class)).isEqualTo(1);
            assertThat(planService.getCurrentVersion(FARM, PLAN_DATE).versionNo()).isEqualTo(2);

            if (bound.get() == 1) {
                PlanOrderReceiveResponse order = boundResponses.get(0);
                // 冻结基数必须来自 v1（1500），绝不可能读到 v2（800）。
                assertThat(order.farmParticipatingKw()).isEqualTo(4500);
                assertThat(order.allocations()).allSatisfy(a -> {
                    assertThat(a.plannedPowerKw()).isEqualTo(1500);
                    assertThat(a.availablePowerKw()).isEqualTo(1500);
                });
                OrderBasisResponse basis = dispatchService.basis(order.orderNo());
                assertThat(basis.planVersionNo()).isEqualTo(1);
                assertThat(basis.versionCurrent()).isFalse();
            }
            // 每轮至多一条指令。
            assertThat(jdbc.queryForObject(
                    "SELECT COUNT(*) FROM curtail_order", Integer.class)).isEqualTo(bound.get());
        }
    }

    @Test
    @DisplayName("同一 requestId 并发受理计划绑定指令：唯一受理，其余重放首次结果")
    void sameRequestIdConcurrentPlanOrderOnce() throws Exception {
        planService.publish(new PlanPublishRequest("base-plan", FARM, PLAN_DATE, 1,
                localInstant(9, 0, 0), fullSegments(1500)));

        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<PlanOrderReceiveResponse>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            PlanOrderReceiveRequest req = new PlanOrderReceiveRequest(
                    "bind-same", FARM, 3000, PLAN_DATE, SEGMENT, 1, localInstant(10, 0, 0));
            futures.add(pool.submit(() -> {
                start.await();
                return dispatchService.receivePlanOrder(req);
            }));
        }
        start.countDown();

        List<PlanOrderReceiveResponse> results = new ArrayList<>();
        for (Future<PlanOrderReceiveResponse> f : futures) {
            results.add(f.get(30, TimeUnit.SECONDS));
        }
        pool.shutdown();

        assertThat(results.stream().map(PlanOrderReceiveResponse::orderId).distinct().count())
                .isEqualTo(1);
        assertThat(results.stream().filter(r -> !r.replayed()).count()).isEqualTo(1);
        assertThat(results.stream().filter(PlanOrderReceiveResponse::replayed).count())
                .isEqualTo(threads - 1);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM curtail_order", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM curtail_order WHERE active_flag='Y'",
                Integer.class)).isEqualTo(1);
    }
}
