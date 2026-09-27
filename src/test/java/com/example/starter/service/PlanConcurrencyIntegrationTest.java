package com.example.starter.service;

import java.time.LocalDate;
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
import com.example.starter.dto.OrderReceiveRequest;
import com.example.starter.dto.PlanBaseEvidenceResponse;
import com.example.starter.dto.PlanPublishRequest;
import com.example.starter.dto.PlanPublishRequest.TurbinePlan;
import com.example.starter.web.ApiConflictException;
import com.example.starter.web.ApiUnprocessableException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * H2 真实并发测试：计划发布与指令受理在风场行锁上竞争时，版本与冻结基数必须一致；
 * 同 requestId 并发发布唯一受理；并发指针替换后同场同日仍恰一个当前版本。
 */
@SpringBootTest
class PlanConcurrencyIntegrationTest extends AbstractIntegrationTest {

    private static final LocalDate PLAN_DATE = LocalDate.of(2026, 9, 27);
    private static final int PERIOD = 9;

    @Autowired
    private PlanService planService;

    @Autowired
    private DispatchService dispatchService;

    @BeforeEach
    void setUp() {
        resetDb();
        pinClock(10, 0, 0);
    }

    private List<Integer> periods(int constant) {
        List<Integer> values = new ArrayList<>(PlanService.PERIODS_PER_DAY);
        for (int i = 0; i < PlanService.PERIODS_PER_DAY; i++) {
            values.add(constant);
        }
        return values;
    }

    private PlanPublishRequest plan(String requestId, int expectedVersion,
                                    int kw01, int kw02, int kw03) {
        return new PlanPublishRequest(requestId, FARM, PLAN_DATE, expectedVersion, List.of(
                new TurbinePlan("T-01", periods(kw01)),
                new TurbinePlan("T-02", periods(kw02)),
                new TurbinePlan("T-03", periods(kw03))));
    }

    private boolean isCausedBy(Throwable ex, Class<?> type) {
        for (Throwable t = ex; t != null; t = t.getCause()) {
            if (type.isInstance(t)) {
                return true;
            }
        }
        return false;
    }

    @Test
    @DisplayName("同一 requestId 并发发布：唯一受理，其余重放首次结果")
    void sameRequestIdConcurrentPublishAcceptedOnce() throws Exception {
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                start.await();
                return planService.publish(plan("same-plan-key", 0, 1800, 1500, 2000));
            }));
        }
        start.countDown();

        AtomicInteger first = new AtomicInteger();
        AtomicInteger replay = new AtomicInteger();
        for (Future<?> f : futures) {
            com.example.starter.dto.PlanPublishResponse resp =
                    (com.example.starter.dto.PlanPublishResponse) f.get(30, TimeUnit.SECONDS);
            if (resp.replayed()) {
                replay.incrementAndGet();
            } else {
                first.incrementAndGet();
            }
        }
        pool.shutdown();

        assertThat(first.get()).isEqualTo(1);
        assertThat(replay.get()).isEqualTo(threads - 1);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM day_ahead_plan", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM day_ahead_plan WHERE is_current = 'Y'", Integer.class))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM idempotent_record", Integer.class)).isEqualTo(1);
    }

    @Test
    @DisplayName("不同 requestId 并发发布同一 expectedVersion=1：仅一个成功，其余 409，指针唯一")
    void concurrentPublishesSameExpectedVersionOnlyOneWins() throws Exception {
        planService.publish(plan("seed-v1", 0, 1800, 1500, 2000));

        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            final int idx = i;
            futures.add(pool.submit(() -> {
                start.await();
                return planService.publish(plan("race-publish-" + idx, 1, 1700, 1400, 1900));
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
                assertThat(isCausedBy(ex, ApiConflictException.class)).isTrue();
                conflict++;
            }
        }
        pool.shutdown();

        assertThat(success).isEqualTo(1);
        assertThat(conflict).isEqualTo(threads - 1);
        assertThat(planService.currentPlan(FARM, PLAN_DATE).version()).isEqualTo(2);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM day_ahead_plan", Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM day_ahead_plan WHERE is_current = 'Y'", Integer.class))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("发布与指令受理竞争：指令绑定版本与其冻结基数一致；一方提交后另一方按新状态裁决")
    void publishCompetingWithOrderAcceptanceIsConsistent() throws Exception {
        // 先发布 v1。
        planService.publish(plan("race-v1", 0, 1900, 1500, 2300));

        int pairs = 8;
        ExecutorService pool = Executors.newFixedThreadPool(pairs * 2);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> publishFutures = new ArrayList<>();
        List<Future<?>> orderFutures = new ArrayList<>();

        // 一半线程用 expectedVersion=1 发布 v2，一半线程绑定 v1 受理目标 4500（同一接收时刻）。
        for (int i = 0; i < pairs; i++) {
            final int idx = i;
            publishFutures.add(pool.submit(() -> {
                start.await();
                return planService.publish(plan("race-v2-" + idx, 1, 400, 400, 500));
            }));
            orderFutures.add(pool.submit(() -> {
                start.await();
                return dispatchService.receive(new OrderReceiveRequest(
                        "race-order-" + idx, FARM, 4500, PLAN_DATE, PERIOD, 1));
            }));
        }
        start.countDown();

        int publishOk = 0;
        int publishConflict = 0;
        for (Future<?> f : publishFutures) {
            try {
                f.get(30, TimeUnit.SECONDS);
                publishOk++;
            } catch (Exception ex) {
                assertThat(isCausedBy(ex, ApiConflictException.class)).isTrue();
                publishConflict++;
            }
        }
        int orderOk = 0;
        int orderExpired = 0;
        int orderSameTime = 0;
        for (Future<?> f : orderFutures) {
            try {
                f.get(30, TimeUnit.SECONDS);
                orderOk++;
            } catch (Exception ex) {
                // 发布先提交：绑定 v1 过期 422；指令先受理：其余同刻指令 409。
                if (isCausedBy(ex, ApiUnprocessableException.class)) {
                    orderExpired++;
                } else if (isCausedBy(ex, ApiConflictException.class)) {
                    orderSameTime++;
                } else {
                    throw ex;
                }
            }
        }
        pool.shutdown();

        // 恰好一个 v2 发布成功。
        assertThat(publishOk).isEqualTo(1);
        assertThat(publishConflict).isEqualTo(pairs - 1);
        // 指令侧计数自洽；同一接收时刻至多一条成功（既有同刻互斥规则）。
        assertThat(orderOk + orderExpired + orderSameTime).isEqualTo(pairs);
        assertThat(orderOk).isBetween(0, 1);

        Integer currentCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM day_ahead_plan WHERE is_current = 'Y'", Integer.class);
        assertThat(currentCount).isEqualTo(1);

        // 每条绑定 v1 受理成功的指令：冻结版本必须是 v1，基数必须是 v1 的上限（1900/1500/2300），
        // 与受理瞬间校验的版本一致——发布先提交的线程必因“过期版本”422 而无指令落库。
        List<Long> v1OrderIds = jdbc.queryForList(
                "SELECT id FROM curtail_order WHERE plan_version = 1", Long.class);
        assertThat(v1OrderIds).hasSize(orderOk);
        for (Long orderId : v1OrderIds) {
            String orderNo = jdbc.queryForObject(
                    "SELECT order_no FROM curtail_order WHERE id = ?", String.class, orderId);
            PlanBaseEvidenceResponse evidence = dispatchService.planBaseEvidence(orderNo);
            assertThat(evidence.planVersion()).isEqualTo(1);
            assertThat(evidence.items()).hasSize(3);
            var byCode = new java.util.HashMap<String, PlanBaseEvidenceResponse.BaseItem>();
            evidence.items().forEach(b -> byCode.put(b.turbineCode(), b));
            assertThat(byCode.get("T-01").plannedKw()).isEqualTo(1900);
            assertThat(byCode.get("T-02").plannedKw()).isEqualTo(1500);
            assertThat(byCode.get("T-03").plannedKw()).isEqualTo(2300);
            // 可参与基数恰为 min(实时可用, 计划)。
            assertThat(byCode.get("T-01").baseKw()).isEqualTo(
                    Math.min(byCode.get("T-01").availableKw(), 1900));
            assertThat(byCode.get("T-02").baseKw()).isEqualTo(
                    Math.min(byCode.get("T-02").availableKw(), 1500));
            assertThat(byCode.get("T-03").baseKw()).isEqualTo(
                    Math.min(byCode.get("T-03").availableKw(), 2300));
        }
        Integer v2Count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM day_ahead_plan WHERE version = 2", Integer.class);
        assertThat(v2Count).isEqualTo(1);
        // 没有任何指令能绑定“竞争中新发布的”v2（请求都声明绑定 v1）。
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM curtail_order WHERE plan_version = 2", Integer.class))
                .isZero();
        // 成功指令数与裁决顺序一致：有指令先受理则成功 1 条；发布先提交则成功 0 条。
        assertThat(orderOk == 1 || orderExpired == pairs).isTrue();
    }
}
