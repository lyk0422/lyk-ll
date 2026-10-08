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
import com.example.starter.web.dto.HandoverResponse;
import com.example.starter.web.dto.Requests.ApplyRequest;
import com.example.starter.web.dto.Requests.ConfirmRequest;
import com.example.starter.web.dto.Requests.HandoverApplyRequest;
import com.example.starter.web.dto.Requests.HandoverCompleteRequest;
import com.example.starter.web.dto.Requests.HandoverConfirmRequest;
import com.example.starter.web.dto.Requests.ScheduleRequest;
import com.example.starter.web.dto.Requests.SettleRequest;
import com.example.starter.web.dto.Requests.StartRequest;

/**
 * 交班并发边界测试（真实线程 + H2 行锁/事务）：
 * 核销与切换竞争时不允许“已核销又切入新引航员”；同一接班员并发交班预占恰一个成功。
 * 全部并发操作带超时，最终以数据库一致状态为准。
 */
@SpringBootTest
@Import(TestClockConfig.class)
class HandoverConcurrencyTest {

    private static final LocalDateTime NOW = TestClockConfig.BASE_TIME;
    private static final LocalDateTime DAY = NOW.toLocalDate().plusDays(1).atTime(0, 0);

    @Autowired
    private HandoverService handoverService;

    @Autowired
    private PilotPlanService planService;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM idempotency_record");
        jdbc.update("DELETE FROM violation_record");
        jdbc.update("DELETE FROM resource_lock");
        jdbc.update("DELETE FROM plan_tug");
        jdbc.update("DELETE FROM pilot_handover");
        jdbc.update("DELETE FROM pilot_plan");
        TestClockConfig.mutableClock().setNow(NOW);
    }

    private long executingPlan(String tag, long pilotId, List<Long> tugIds,
                               LocalDateTime start, LocalDateTime end) {
        long planId = planService.apply(new ApplyRequest(
                tag + "-apply", "测试船代", "合成轮-" + tag, start, end)).planId();
        planService.schedule(new ScheduleRequest(tag + "-sched", planId, pilotId, tugIds,
                start.minusHours(1), end.plusHours(1)));
        planService.confirm(new ConfirmRequest(tag + "-confirm", planId));
        planService.start(new StartRequest(tag + "-start", planId));
        return planId;
    }

    private long signedHandover(String tag, long planId, long from, long to) {
        HandoverResponse applied = handoverService.apply(new HandoverApplyRequest(
                tag + "-ho", planId, from, to, DAY.plusHours(2)));
        handoverService.confirm(new HandoverConfirmRequest(
                tag + "-from", applied.handoverId(), from, "交", 1L));
        handoverService.confirm(new HandoverConfirmRequest(
                tag + "-to", applied.handoverId(), to, "接", 2L));
        return applied.handoverId();
    }

    @Test
    @DisplayName("核销与切换并发竞争：不出现已核销又切入新引航员，终态责任一致")
    void settleVersusSwitchNeverCutsInAfterSettle() throws Exception {
        long planId = executingPlan("CC", 1L, List.of(1L, 2L), DAY.plusHours(0), DAY.plusHours(4));
        long handoverId = signedHandover("CC", planId, 1L, 2L);
        TestClockConfig.mutableClock().setNow(DAY.plusHours(2));

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        AtomicInteger settleOk = new AtomicInteger();
        AtomicInteger switchOk = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();
        try {
            Future<?> fSettle = pool.submit(() -> {
                awaitGate(ready, go);
                try {
                    planService.settle(new SettleRequest("CC-settle", planId));
                    settleOk.incrementAndGet();
                } catch (ApiException e) {
                    rejected.incrementAndGet();
                }
            });
            Future<?> fSwitch = pool.submit(() -> {
                awaitGate(ready, go);
                try {
                    handoverService.complete(new HandoverCompleteRequest("CC-complete", handoverId, 3L));
                    switchOk.incrementAndGet();
                } catch (ApiException e) {
                    rejected.incrementAndGet();
                }
            });
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            go.countDown();
            fSettle.get(30, TimeUnit.SECONDS);
            fSwitch.get(30, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }

        String planStatus = jdbc.queryForObject(
                "SELECT status FROM pilot_plan WHERE id = ?", String.class, planId);
        Long pilot = jdbc.queryForObject(
                "SELECT pilot_id FROM pilot_plan WHERE id = ?", Long.class, planId);
        String handoverStatus = jdbc.queryForObject(
                "SELECT status FROM pilot_handover WHERE id = ?", String.class, handoverId);
        Integer locks = jdbc.queryForObject(
                "SELECT COUNT(*) FROM resource_lock WHERE plan_id = ?", Integer.class, planId);

        // 关键不变量：交班单关闭（核销抢先）则绝不能切入新引航员；切入成功则责任人必为接班员
        if ("CLOSED".equals(handoverStatus)) {
            assertThat(pilot).isEqualTo(1L);
            assertThat(planStatus).isEqualTo("SETTLED");
            assertThat(switchOk.get()).isZero();
        } else if ("SWITCHED".equals(handoverStatus)) {
            assertThat(pilot).isEqualTo(2L);
        } else {
            throw new AssertionError("交班单终态非法: " + handoverStatus);
        }
        // 不允许 PENDING 残留；核销后（无论先后）锁应清空
        assertThat(handoverStatus).isIn("CLOSED", "SWITCHED");
        if ("SETTLED".equals(planStatus)) {
            assertThat(locks).isZero();
        }
        // 至少核销成功一次；切换要么成功一次要么被核销抢先拒绝，绝不重复切入
        assertThat(settleOk.get()).isEqualTo(1);
        assertThat(switchOk.get()).isBetween(0, 1);
    }

    @Test
    @DisplayName("同一接班员并发交班同一剩余时段：恰一个申请成功，失败者无接班预占残留")
    void concurrentHandoverSameSuccessorExactlyOneWins() throws Exception {
        // 两条独立执行中计划（当前责任人 P1/P3、不同拖轮），同时申请把剩余段交给同一 P2
        long planA = executingPlan("CS-A", 1L, List.of(1L, 2L), DAY.plusHours(0), DAY.plusHours(4));
        long planB = executingPlan("CS-B", 3L, List.of(3L, 4L), DAY.plusHours(0), DAY.plusHours(4));

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        AtomicInteger success = new AtomicInteger();
        AtomicInteger conflict = new AtomicInteger();
        try {
            Future<?> f1 = pool.submit(() -> {
                awaitGate(ready, go);
                try {
                    handoverService.apply(new HandoverApplyRequest(
                            "CS-ho-A", planA, 1L, 2L, DAY.plusHours(2)));
                    success.incrementAndGet();
                } catch (ApiException e) {
                    conflict.incrementAndGet();
                }
            });
            Future<?> f2 = pool.submit(() -> {
                awaitGate(ready, go);
                try {
                    handoverService.apply(new HandoverApplyRequest(
                            "CS-ho-B", planB, 3L, 2L, DAY.plusHours(2)));
                    success.incrementAndGet();
                } catch (ApiException e) {
                    conflict.incrementAndGet();
                }
            });
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            go.countDown();
            f1.get(30, TimeUnit.SECONDS);
            f2.get(30, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }

        assertThat(success.get()).isEqualTo(1);
        assertThat(conflict.get()).isEqualTo(1);
        // P2 的接班预占最终只挂在一条计划上
        Integer p2Preemptions = jdbc.queryForObject(
                "SELECT COUNT(*) FROM resource_lock WHERE resource_type = 'PILOT' AND resource_id = 2",
                Integer.class);
        assertThat(p2Preemptions).isEqualTo(1);
        // 两条原引航员（P1、P3）整段占用都保留
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM resource_lock WHERE resource_type = 'PILOT' AND resource_id = 1",
                Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM resource_lock WHERE resource_type = 'PILOT' AND resource_id = 3",
                Integer.class)).isEqualTo(1);
    }

    private void awaitGate(CountDownLatch ready, CountDownLatch go) {
        try {
            ready.countDown();
            go.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        }
    }
}
