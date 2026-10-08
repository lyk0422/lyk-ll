package com.example.starter.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import com.example.starter.TestClockConfig;
import com.example.starter.web.ApiException;
import com.example.starter.web.dto.Requests.ApplyRequest;
import com.example.starter.web.dto.Requests.ConfirmRequest;
import com.example.starter.web.dto.Requests.HandoverAckRequest;
import com.example.starter.web.dto.Requests.HandoverApplyRequest;
import com.example.starter.web.dto.Requests.ScheduleRequest;
import com.example.starter.web.dto.Requests.SettleRequest;
import com.example.starter.web.dto.Requests.StartRequest;

/**
 * 交班并发边界测试（真实线程 + H2 行锁）：核销与切换竞争由计划行锁串行化，
 * 最终状态必然一致——要么核销先提交（交班关闭、原人保留），要么切换先提交
 * （责任切入接班员后核销），绝不出现已核销又切入新引航员或交班悬而未决。
 */
@SpringBootTest
@Import(TestClockConfig.class)
class HandoverConcurrencyTest {

    private static final LocalDateTime DAY =
            TestClockConfig.BASE_TIME.toLocalDate().plusDays(1).atTime(0, 0);

    @Autowired
    private PilotPlanService planService;

    @Autowired
    private HandoverService handoverService;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM idempotency_record");
        jdbc.update("DELETE FROM violation_record");
        jdbc.update("DELETE FROM resource_lock");
        jdbc.update("DELETE FROM pilot_handover");
        jdbc.update("DELETE FROM plan_tug");
        jdbc.update("DELETE FROM pilot_plan");
        TestClockConfig.mutableClock().setNow(TestClockConfig.BASE_TIME);
    }

    @Test
    @DisplayName("核销与切换竞争：最终状态一致，交班不悬置，预占必释放")
    void settleVersusSwitchRaceIsConsistent() throws Exception {
        // 00:00-04:00 执行中，P1 负责；02:00 交班 P1→P2，双方已签认
        long planId = planService.apply(new ApplyRequest(
                "RC-apply", "测试船代", "合成轮", DAY, DAY.plusHours(4))).planId();
        planService.schedule(new ScheduleRequest("RC-sched", planId, 1L, List.of(1L),
                DAY.minusHours(1), DAY.plusHours(5)));
        planService.confirm(new ConfirmRequest("RC-confirm", planId));
        planService.start(new StartRequest("RC-start", planId));
        long handoverId = handoverService.apply(
                new HandoverApplyRequest("RC-ho", planId, 1L, 2L, DAY.plusHours(2))).handoverId();
        handoverService.ack(new HandoverAckRequest("RC-ack1", handoverId, 1L, "交", 1L));
        handoverService.ack(new HandoverAckRequest("RC-ack2", handoverId, 2L, "接", 2L));
        // 当前时刻到达约定交班时刻：此时 ack 会触发切换，与核销形成竞争
        TestClockConfig.mutableClock().setNow(DAY.plusHours(2));

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        AtomicReference<String> ackOutcome = new AtomicReference<>();
        AtomicReference<String> settleOutcome = new AtomicReference<>();
        try {
            Future<?> switcher = pool.submit(() -> {
                try {
                    ready.countDown();
                    go.await(10, TimeUnit.SECONDS);
                    handoverService.ack(new HandoverAckRequest("RC-ack3", handoverId, 2L, null, 3L));
                    ackOutcome.set("SWITCHED");
                } catch (ApiException e) {
                    ackOutcome.set("REJECTED");
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });
            Future<?> settler = pool.submit(() -> {
                try {
                    ready.countDown();
                    go.await(10, TimeUnit.SECONDS);
                    planService.settle(new SettleRequest("RC-settle", planId));
                    settleOutcome.set("SETTLED");
                } catch (ApiException e) {
                    settleOutcome.set("REJECTED");
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            go.countDown();
            switcher.get(30, TimeUnit.SECONDS);
            settler.get(30, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }

        // 核销总能完成（切换不改变计划执行中状态）；切换仅在核销前到达才成功
        assertThat(settleOutcome.get()).isEqualTo("SETTLED");
        String planStatus = jdbc.queryForObject(
                "SELECT status FROM pilot_plan WHERE id = ?", String.class, planId);
        String handoverStatus = jdbc.queryForObject(
                "SELECT status FROM pilot_handover WHERE id = ?", String.class, handoverId);
        long pilotId = jdbc.queryForObject(
                "SELECT pilot_id FROM pilot_plan WHERE id = ?", Long.class, planId);
        Integer locks = jdbc.queryForObject(
                "SELECT COUNT(*) FROM resource_lock WHERE plan_id = ?", Integer.class, planId);

        assertThat(planStatus).isEqualTo("SETTLED");
        // 交班不悬置：必为已切换或核销关闭
        assertThat(handoverStatus).isIn("SWITCHED", "CLOSED");
        // 核销释放全部锁（含接班预占）
        assertThat(locks).isZero();
        if ("CLOSED".equals(handoverStatus)) {
            // 核销先提交：交班关闭，不得切入新引航员
            assertThat(ackOutcome.get()).isEqualTo("REJECTED");
            assertThat(pilotId).isEqualTo(1L);
        } else {
            // 切换先提交：责任已切入接班员，随后核销
            assertThat(ackOutcome.get()).isEqualTo("SWITCHED");
            assertThat(pilotId).isEqualTo(2L);
        }
    }
}
