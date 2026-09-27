package com.example.starter.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Map;
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
import com.example.starter.dto.PlanOrderReceiveRequest;
import com.example.starter.dto.PlanOrderReceiveResponse;
import com.example.starter.web.ApiConflictException;
import com.example.starter.web.ApiUnprocessableException;

/**
 * 日前计划发布与指令受理真实并发测试（H2 行锁）：
 * 发布/受理竞争时校验的版本与冻结基数必须一致；并发发布/受理满足版本与幂等不变量。
 */
@SpringBootTest
class PlanConcurrencyIntegrationTest extends AbstractIntegrationTest {

    private static final String DATE = PlanFixtures.DATE;
    private static final Instant T0 = PlanFixtures.OP_TIME;
    private static final Instant OP_ORDER = Instant.parse("2026-09-27T02:30:00Z");

    @Autowired
    private PlanService planService;

    @BeforeEach
    void setUp() {
        resetDb();
        pinClock(10, 0, 0);
    }

    private com.example.starter.dto.PlanPublishRequest plan(String requestId, int expected,
                                                             Instant op, int t1) {
        return PlanFixtures.uniformPlan(requestId, FARM, expected, op,
                "T-01", String.valueOf(t1), "T-02", "1800", "T-03", "2400");
    }

    @Test
    @DisplayName("同 requestId 并发发布：唯一受理产生一个版本，其余全部重放首次结果")
    void sameRequestIdConcurrentPublishOnce() throws Exception {
        int threads = 6;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<com.example.starter.dto.PlanPublishResponse>> futures = new java.util.ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                start.await();
                return planService.publish(plan("pub-same", 0, T0, 1500));
            }));
        }
        start.countDown();

        Long planId = null;
        int first = 0;
        int replay = 0;
        for (Future<com.example.starter.dto.PlanPublishResponse> f : futures) {
            var r = f.get(30, TimeUnit.SECONDS);
            if (r.replayed()) {
                replay++;
            } else {
                first++;
            }
            if (planId == null) {
                planId = r.planId();
            }
            assertThat(r.planId()).isEqualTo(planId);
        }
        pool.shutdown();
        assertThat(first).isEqualTo(1);
        assertThat(replay).isEqualTo(threads - 1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM day_ahead_plan", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM idempotent_record WHERE request_id='pub-same'", Integer.class))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("并发发布同一新版本（都传 expectedVersion=1）：恰一个成功，另一个 409，版本不跳号")
    void concurrentPublishSameExpectedVersionOnlyOneWins() throws Exception {
        planService.publish(plan("pub-base", 0, T0, 1500));

        int threads = 6;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new java.util.ArrayList<>();
        for (int i = 0; i < threads; i++) {
            final int idx = i;
            futures.add(pool.submit(() -> {
                start.await();
                return planService.publish(plan("pub-v2-" + idx, 1,
                        Instant.parse("2026-09-26T02:0" + idx + ":00Z"), 1400 - idx));
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
        // 仅版本 1、2，无跳号/重复。
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM day_ahead_plan", Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM day_ahead_plan WHERE farm_code=? AND plan_date=? AND current_flag='Y'",
                Integer.class, FARM, DATE)).isEqualTo(1);
    }

    @Test
    @DisplayName("发布与指令受理竞争：受理要么按 v1 冻结 v1 基数，要么 422，绝不绑定到新发布的 v2")
    void publishAndAcceptCompeteKeepVersionAndBasisConsistent() throws Exception {
        // 已发布版本 1：T-01 计划 1500。
        var v1 = planService.publish(plan("pub-race-1", 0, T0, 1500));

        // 受理方始终期望版本 1；发布方把 T-01 计划改为 200，发布版本 2。
        PlanOrderReceiveRequest acceptReq =
                new PlanOrderReceiveRequest("po-race", FARM, 4000, DATE, 10, 1, OP_ORDER);
        com.example.starter.dto.PlanPublishRequest publishReq =
                plan("pub-race-2", 1, Instant.parse("2026-09-27T02:30:00Z"), 200);

        // 多轮竞争提高两种先后顺序都被覆盖的概率。
        boolean sawAcceptFirst = false;
        boolean sawPublishFirst = false;
        for (int round = 0; round < 8; round++) {
            // 每轮使用新的指令/发布幂等键；版本号在受理成功时不变，发布成功时 +1。
            Integer currentVersionAtStart = jdbc.queryForObject(
                    "SELECT version_no FROM day_ahead_plan WHERE farm_code=? AND plan_date=? AND current_flag='Y'",
                    Integer.class, FARM, DATE);
            int expected = currentVersionAtStart;
            PlanOrderReceiveRequest accept = new PlanOrderReceiveRequest(
                    "po-race-" + round, FARM, 4000, DATE, 10, expected,
                    OP_ORDER.plusSeconds(round));
            com.example.starter.dto.PlanPublishRequest publish = plan(
                    "pub-race-2-" + round, expected,
                    Instant.parse("2026-09-27T02:30:0" + round + "Z"), 200);

            ExecutorService pool = Executors.newFixedThreadPool(2);
            CountDownLatch start = new CountDownLatch(1);
            Future<PlanOrderReceiveResponse> acceptFuture = pool.submit(() -> {
                start.await();
                return planService.receivePlanOrder(accept);
            });
            Future<?> publishFuture = pool.submit(() -> {
                start.await();
                return planService.publish(publish);
            });
            start.countDown();

            boolean accepted = false;
            boolean published = false;
            try {
                acceptFuture.get(30, TimeUnit.SECONDS);
                accepted = true;
            } catch (Exception ex) {
                // 受理落后于发布 → 期望版本过期：必须 422（而不是绑定新版本）。
                assertThat(ex.getCause()).isInstanceOf(ApiUnprocessableException.class);
            }
            try {
                publishFuture.get(30, TimeUnit.SECONDS);
                published = true;
            } catch (Exception ex) {
                // 发布落后时 expectedVersion 也可能已过期 → 409，属合法竞争结果。
                assertThat(ex.getCause()).isInstanceOf(ApiConflictException.class);
            }
            pool.shutdown();

            if (accepted) {
                sawAcceptFirst = true;
                PlanOrderReceiveResponse order = acceptFuture.get();
                // 受理先于发布：绑定的必须仍是它校验的那个版本，且冻结该版本的计划值。
                assertThat(order.versionNo()).isEqualTo(expected);
                assertBasisMatchesBoundVersion(order);
            } else {
                sawPublishFirst = true;
            }
            // 每轮发布要么成功要么冲突；最终始终恰一个当前版本。
            assertThat(published || !accepted).isTrue();
        }

        // 竞争两种顺序都应实际发生（极低概率缺失时给出明确信息）。
        assertThat(sawAcceptFirst).as("至少一轮受理先于发布").isTrue();
        assertThat(sawPublishFirst).as("至少一轮发布先于受理").isTrue();

        // v1 版本头与条目自始至终不可变：T-01 在 v1 仍是 1500。
        Integer v1t1 = jdbc.queryForObject(
                "SELECT planned_power_kw FROM day_ahead_plan_entry WHERE plan_id=? AND segment_index=10 "
                        + "AND turbine_code='T-01'", Integer.class, v1.planId());
        assertThat(v1t1).isEqualTo(1500);
    }

    /** 断言受理指令冻结的计划值与其绑定版本该段计划完全一致，参与值=min(实时,计划)。 */
    private void assertBasisMatchesBoundVersion(PlanOrderReceiveResponse order) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT b.turbine_code, b.planned_power_kw AS basis_planned,
                       b.realtime_available_kw, b.participating_kw,
                       e.planned_power_kw AS version_planned,
                       t.available_power_kw AS current_available
                FROM order_plan_basis b
                JOIN curtail_order o ON o.id = b.order_id
                JOIN day_ahead_plan_entry e
                  ON e.plan_id = o.plan_id AND e.segment_index = o.segment_index
                  AND e.turbine_code = b.turbine_code
                JOIN wind_turbine t ON t.id = b.turbine_id
                WHERE b.order_id = ?
                """, order.orderId());
        assertThat(rows).isNotEmpty();
        for (Map<String, Object> r : rows) {
            int basisPlanned = ((Number) r.get("BASIS_PLANNED")).intValue();
            int versionPlanned = ((Number) r.get("VERSION_PLANNED")).intValue();
            int realtime = ((Number) r.get("REALTIME_AVAILABLE_KW")).intValue();
            int participating = ((Number) r.get("PARTICIPATING_KW")).intValue();
            assertThat(basisPlanned).isEqualTo(versionPlanned);
            assertThat(participating).isEqualTo(Math.min(realtime, versionPlanned));
        }
    }
}
