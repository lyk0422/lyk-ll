package com.example.starter.atm;

import com.example.starter.atm.domain.PlanStatus;
import com.example.starter.atm.repo.AtmRepository;
import com.example.starter.atm.repo.GuardRepository;
import com.example.starter.atm.repo.PlanRepository;
import com.example.starter.atm.service.ReplenishService;
import com.example.starter.atm.support.DomainClock;
import com.example.starter.atm.web.ApiException;
import com.example.starter.atm.web.dto.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ATM 加钞计划 H2（MODE=MySQL）事务与并发集成测试：
 * 恰等容量、双人打卡、恰 4 小时异常、三方一致核销、长短款挂账原子性、
 * 第 3 日销账边界、逾期冻结、同键重放、并发唯一受理、封存终态迟到数据。
 */
@SpringBootTest
class ReplenishServiceH2Test {

    private static final long FOUR_HOURS = 4L * 60 * 60 * 1000;
    private static final Instant T0 = Instant.parse("2026-01-10T00:00:00Z");

    @Autowired
    private ReplenishService service;
    @Autowired
    private DomainClock clock;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private PlanRepository planRepository;
    @Autowired
    private AtmRepository atmRepository;
    @Autowired
    private GuardRepository guardRepository;

    @BeforeEach
    void clean() {
        clock.reset();
        jdbc.execute("DELETE FROM plan_event");
        jdbc.execute("DELETE FROM escort_checkin");
        jdbc.execute("DELETE FROM over_short");
        jdbc.execute("DELETE FROM idempotent_request");
        jdbc.execute("DELETE FROM replenish_plan");
        jdbc.execute("UPDATE cash_box SET status = 'AVAILABLE', loaded_amount = 0");
        jdbc.execute("UPDATE atm SET escort_frozen = 0");
        jdbc.execute("UPDATE guard SET frozen = 0");
        clock.setFixed(T0);
    }

    @AfterEach
    void resetClock() {
        clock.reset();
    }

    private static String rid() {
        return UUID.randomUUID().toString();
    }

    private PlanResponse prepare(String atmNo, String boxNo, Long amount) {
        return service.prepare(new PreparePlanRequest(rid(), atmNo, boxNo, amount));
    }

    // ---------- 预测编制与容量边界 ----------

    @Test
    void prepare_predictedAmountFromHistoryWhenAbsent() {
        PlanResponse p = prepare("ATM-001", "BOX-001-1", null);
        assertEquals("PREPARED", p.status());
        // 未显式指定时按历史日均取现（3000 元=300000 分）预测
        assertEquals(300_000_00L, p.plannedAmount());
        assertEquals(300_000_00L, p.predictedAmount());
    }

    @Test
    void prepare_amountExactlyEqualToCapacityIsLegal() {
        PlanResponse p = prepare("ATM-001", "BOX-001-1", 500_000_00L);
        assertEquals("PREPARED", p.status());
        assertEquals(500_000_00L, p.plannedAmount());
    }

    @Test
    void prepare_amountExceedsCapacityRejected422() {
        ApiException ex = assertThrows(ApiException.class,
                () -> prepare("ATM-001", "BOX-001-1", 500_000_01L));
        assertEquals(422, ex.getStatus().value());
    }

    @Test
    void prepare_boxOfAnotherAtmRejected422() {
        ApiException ex = assertThrows(ApiException.class,
                () -> prepare("ATM-001", "BOX-002-1", 100_000_00L));
        assertEquals(422, ex.getStatus().value());
    }

    // ---------- 双人押运打卡 ----------

    @Test
    void escort_requiresTwoDistinctGuardsAndRejectsSameGuardRepeat() {
        PlanResponse p = happyToApproved(100_000_00L);

        CheckinResponse c1 = service.checkin(p.planNo(),
                new CheckinRequest(rid(), "GRD-A"));
        assertEquals(1, c1.checkins());
        assertFalse(c1.escortCompleted());
        assertEquals("IN_TRANSIT", c1.status());

        // 单人打卡不生效（押运未完成）
        assertEquals(PlanStatus.IN_TRANSIT, planRepository.findByPlanNo(p.planNo()).getStatus());

        // 同人重复打卡拒绝
        ApiException dup = assertThrows(ApiException.class, () -> service.checkin(p.planNo(),
                new CheckinRequest(rid(), "GRD-A")));
        assertEquals(409, dup.getStatus().value());

        CheckinResponse c2 = service.checkin(p.planNo(),
                new CheckinRequest(rid(), "GRD-B"));
        assertEquals(2, c2.checkins());
        assertTrue(c2.escortCompleted());
        assertEquals("ESCORT_DONE", c2.status());
    }

    @Test
    void escort_frozenGuardCannotTakeNewTask() {
        guardRepository.markFrozen(guardRepository.findByNo("GRD-A").id());
        PlanResponse p = happyToApproved(100_000_00L);
        ApiException ex = assertThrows(ApiException.class, () -> service.checkin(p.planNo(),
                new CheckinRequest(rid(), "GRD-A")));
        assertEquals(403, ex.getStatus().value());
    }

    // ---------- 恰满 4 小时押运异常 ----------

    @Test
    void escort_exactlyFourHoursMarksAbnormalAndFreezesAtm() {
        PlanResponse p = happyToApproved(100_000_00L);

        // 差 1 毫秒仍在时限内，首人打卡有效
        clock.setFixed(T0.plusMillis(FOUR_HOURS - 1));
        service.checkin(p.planNo(), new CheckinRequest(rid(), "GRD-A"));

        // 恰满 4 小时次人打卡：自动转押运异常
        clock.setFixed(T0.plusMillis(FOUR_HOURS));
        ApiException ex = assertThrows(ApiException.class, () -> service.checkin(p.planNo(),
                new CheckinRequest(rid(), "GRD-B")));
        assertEquals(422, ex.getStatus().value());

        assertEquals(PlanStatus.ESCORT_ABNORMAL, planRepository.findByPlanNo(p.planNo()).getStatus());
        assertTrue(atmRepository.findByNo("ATM-001").escortFrozen(), "ATM 应被冻结后续计划");

        // 冻结后编制新计划拒绝
        ApiException blocked = assertThrows(ApiException.class,
                () -> prepare("ATM-001", "BOX-001-2", 1_000_00L));
        assertEquals(403, blocked.getStatus().value());

        // 异常计划不能继续押运
        ApiException noMore = assertThrows(ApiException.class, () -> service.checkin(p.planNo(),
                new CheckinRequest(rid(), "GRD-C")));
        assertEquals(422, noMore.getStatus().value());
    }

    @Test
    void escort_sweepAtExactlyDeadlineTimesOutZeroCheckinPlan() {
        PlanResponse p = happyToApproved(100_000_00L);
        clock.setFixed(T0.plusMillis(FOUR_HOURS));
        int n = service.sweepEscortTimeouts();
        assertEquals(1, n);
        assertEquals(PlanStatus.ESCORT_ABNORMAL, planRepository.findByPlanNo(p.planNo()).getStatus());
    }

    @Test
    void escort_checkinOneMillisecondBeforeDeadlineStillAllowed() {
        PlanResponse p = happyToApproved(100_000_00L);
        clock.setFixed(T0.plusMillis(FOUR_HOURS - 1));
        CheckinResponse c = service.checkin(p.planNo(), new CheckinRequest(rid(), "GRD-A"));
        assertEquals(1, c.checkins());
    }

    // ---------- 三方一致核销 ----------

    @Test
    void reconcile_threeWayEqual() {
        PlanResponse p = happyToEscortDone(100_000_00L);
        service.count(p.planNo(), new CountRequest(rid(), "GRD-A", "GRD-B", 100_000_00L));
        PlanResponse confirmed = service.deviceConfirm(p.planNo(),
                new DeviceConfirmRequest(rid(), 100_000_00L));
        assertEquals("RECONCILED", confirmed.status());
        assertNotNull(confirmed.reconciledAt());
        // 无挂账
        Integer osCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM over_short WHERE plan_id = ?", Integer.class,
                planRepository.findByPlanNo(p.planNo()).getId());
        assertEquals(0, osCount);
    }

    // ---------- 长短款挂账原子性 ----------

    @Test
    void overShort_twoOfThreeDiffer_createdAtomicallyInSameTransaction() {
        PlanResponse p = happyToEscortDone(100_000_00L);
        service.count(p.planNo(), new CountRequest(rid(), "GRD-A", "GRD-B", 90_000_00L));
        PlanResponse confirmed = service.deviceConfirm(p.planNo(),
                new DeviceConfirmRequest(rid(), 95_000_00L));
        assertEquals("OVER_SHORT", confirmed.status());
        assertNotNull(confirmed.overShortId());

        // 挂账金额 = 实际 - 计划 = 95 - 100 = -5（短款）
        var row = jdbc.queryForMap(
                "SELECT * FROM over_short WHERE plan_id = ?",
                planRepository.findByPlanNo(p.planNo()).getId());
        assertEquals(-5_000_00L, ((Number) row.get("amount")).longValue());
        assertEquals("SHORT", row.get("direction"));
        assertEquals("OPEN", row.get("status"));
        assertEquals("2026-01-13", String.valueOf(row.get("deadline_utc_date")));

        // 不得核销完成而挂账未记：计划状态与挂账同事务同时可见
        assertEquals(PlanStatus.OVER_SHORT, planRepository.findByPlanNo(p.planNo()).getStatus());
    }

    @Test
    void overShort_longCashAmountPositive() {
        PlanResponse p = happyToEscortDone(100_000_00L);
        service.count(p.planNo(), new CountRequest(rid(), "GRD-A", "GRD-B", 100_000_00L));
        PlanResponse confirmed = service.deviceConfirm(p.planNo(),
                new DeviceConfirmRequest(rid(), 102_000_00L));
        assertEquals("OVER_SHORT", confirmed.status());
        var row = jdbc.queryForMap(
                "SELECT * FROM over_short WHERE id = ?", confirmed.overShortId());
        assertEquals(2_000_00L, ((Number) row.get("amount")).longValue());
        assertEquals("LONG", row.get("direction"));
    }

    // ---------- 第 3 个自然日销账边界与逾期冻结 ----------

    @Test
    void overShort_settleExactlyOnThirdNaturalDayAccepted() {
        long osId = createOverShortPlan();
        PlanResponse plan = service.getPlan(planNoOf(osId));

        // 恰第 3 日最后一毫秒受理合法
        clock.setFixed(Instant.parse("2026-01-13T23:59:59.999Z"));
        PlanResponse settled = service.settleOverShort(plan.planNo(),
                new SettleOverShortRequest(rid()));
        assertEquals("OVER_SHORT_SETTLED", settled.status());

        // 销账后可封存
        PlanResponse sealed = service.seal(plan.planNo(), new SealRequest(rid()));
        assertEquals("SEALED", sealed.status());
    }

    @Test
    void overShort_settleOnFourthDayEscalatesAndFreezesGuards() {
        long osId = createOverShortPlan();
        PlanResponse plan = service.getPlan(planNoOf(osId));

        // 第 4 个自然日提交：逾期升级
        clock.setFixed(Instant.parse("2026-01-14T00:00:00Z"));
        ApiException ex = assertThrows(ApiException.class, () -> service.settleOverShort(plan.planNo(),
                new SettleOverShortRequest(rid())));
        assertEquals(422, ex.getStatus().value());

        assertEquals("ESCALATED", jdbc.queryForObject(
                "SELECT status FROM over_short WHERE id = ?", String.class, osId));
        assertTrue(guardRepository.findByNo("GRD-A").frozen());
        assertTrue(guardRepository.findByNo("GRD-B").frozen());

        // 升级后不能再销账
        ApiException again = assertThrows(ApiException.class, () -> service.settleOverShort(plan.planNo(),
                new SettleOverShortRequest(rid())));
        assertEquals(409, again.getStatus().value());
    }

    // ---------- requestId 幂等：同键同参 / 同键异参 ----------

    @Test
    void idempotency_sameKeySameParamsReturnsFirstResult() {
        String key = rid();
        PlanResponse first = service.prepare(
                new PreparePlanRequest(key, "ATM-002", "BOX-002-1", 50_000_00L));
        PlanResponse replay = service.prepare(
                new PreparePlanRequest(key, "ATM-002", "BOX-002-1", 50_000_00L));
        assertEquals(first.planNo(), replay.planNo());
        assertEquals(first.id(), replay.id());
        assertEquals(1L, planCount());
    }

    @Test
    void idempotency_sameKeyDifferentParamsReturns409() {
        String key = rid();
        service.prepare(new PreparePlanRequest(key, "ATM-002", "BOX-002-1", 50_000_00L));
        ApiException ex = assertThrows(ApiException.class, () -> service.prepare(
                new PreparePlanRequest(key, "ATM-002", "BOX-002-2", 50_000_00L)));
        assertEquals(409, ex.getStatus().value());
        assertEquals(1L, planCount());
    }

    @Test
    void idempotency_failedFirstCallReplaysSameError() {
        String key = rid();
        assertThrows(ApiException.class, () -> service.prepare(
                new PreparePlanRequest(key, "ATM-002", "BOX-002-1", 999_000_00L)));
        ApiException replay = assertThrows(ApiException.class, () -> service.prepare(
                new PreparePlanRequest(key, "ATM-002", "BOX-002-1", 999_000_00L)));
        assertEquals(422, replay.getStatus().value());
        assertEquals(0L, planCount());
    }

    @Test
    void idempotency_checkinSameKeyReplaysWithoutSecondCheckin() {
        PlanResponse p = happyToApproved(100_000_00L);
        String key = rid();
        CheckinResponse first = service.checkin(p.planNo(), new CheckinRequest(key, "GRD-A"));
        CheckinResponse replay = service.checkin(p.planNo(), new CheckinRequest(key, "GRD-A"));
        assertEquals(first.seq(), replay.seq());
        assertEquals(1, replay.checkins());
        assertEquals(PlanStatus.IN_TRANSIT, planRepository.findByPlanNo(p.planNo()).getStatus());

        // 同键改押运员 -> 409
        ApiException conflict = assertThrows(ApiException.class,
                () -> service.checkin(p.planNo(), new CheckinRequest(key, "GRD-B")));
        assertEquals(409, conflict.getStatus().value());
    }

    // ---------- 并发对同一 ATM 编制唯一受理 ----------

    @Test
    void prepare_concurrentSameAtmSameDayOnlyOneAccepted() throws Exception {
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger success = new AtomicInteger();
        AtomicInteger conflict = new AtomicInteger();
        List<Exception> errors = new ArrayList<>();
        try {
            List<java.util.concurrent.Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    try {
                        service.prepare(new PreparePlanRequest(
                                rid(), "ATM-003", "BOX-003-1", 10_000_00L));
                        success.incrementAndGet();
                    } catch (ApiException e) {
                        if (e.getStatus().value() == 409) {
                            conflict.incrementAndGet();
                        } else {
                            synchronized (errors) {
                                errors.add(e);
                            }
                        }
                    } catch (Exception e) {
                        synchronized (errors) {
                            errors.add(e);
                        }
                    }
                    return null;
                }));
            }
            start.countDown();
            for (var f : futures) {
                f.get(10, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }
        assertTrue(errors.isEmpty(), () -> errors.toString());
        assertEquals(1, success.get());
        assertEquals(threads - 1, conflict.get());
        Integer rows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM replenish_plan WHERE atm_id = ? AND plan_utc_date = '2026-01-10'",
                Integer.class, atmRepository.findByNo("ATM-003").id());
        assertEquals(1, rows);
    }

    // ---------- 封存终态与迟到数据 ----------

    @Test
    void seal_repeatedSealIdempotentAndLateDataOnlyRecorded() {
        PlanResponse p = happyToReconciled(100_000_00L);
        String key = rid();
        PlanResponse sealed = service.seal(p.planNo(), new SealRequest(key));
        assertEquals("SEALED", sealed.status());

        // 重复封存幂等成功
        PlanResponse again = service.seal(p.planNo(), new SealRequest(key));
        assertEquals("SEALED", again.status());
        assertEquals(sealed.sealedAt(), again.sealedAt());

        // 封存后迟到的设备确认：只记迟到事件，状态不变
        PlanResponse late = service.deviceConfirm(p.planNo(),
                new DeviceConfirmRequest(rid(), 77_000_00L));
        assertEquals("SEALED", late.status());
        assertTrue(late.late());
        Integer lateEvents = jdbc.queryForObject(
                "SELECT COUNT(*) FROM plan_event WHERE plan_id = ? AND late = 1 "
                        + "AND event_type = 'LATE_DEVICE_CONFIRM'",
                Integer.class, planRepository.findByPlanNo(p.planNo()).getId());
        assertEquals(1, lateEvents);
        // 迟到数据不覆盖原入账数
        assertEquals(100_000_00L, planRepository.findByPlanNo(p.planNo()).getDeviceAmount());
    }

    @Test
    void seal_notAllowedBeforeReconcileOrSettle() {
        PlanResponse p = happyToEscortDone(100_000_00L);
        ApiException ex = assertThrows(ApiException.class,
                () -> service.seal(p.planNo(), new SealRequest(rid())));
        assertEquals(409, ex.getStatus().value());
    }

    @Test
    void sameAtmSameDay_onlyOneActivePlan_terminalFreesSlot() {
        // 同日执行中单唯一：连续编制两单拒绝
        PlanResponse first = prepare("ATM-002", "BOX-002-1", 50_000_00L);
        ApiException conflict = assertThrows(ApiException.class,
                () -> prepare("ATM-002", "BOX-002-2", 50_000_00L));
        assertEquals(409, conflict.getStatus().value());

        // 走完流程并封存（终态）后，同 UTC 日允许再编新单
        service.approve(first.planNo(), new ApproveRequest(rid()));
        service.checkin(first.planNo(), new CheckinRequest(rid(), "GRD-A"));
        service.checkin(first.planNo(), new CheckinRequest(rid(), "GRD-B"));
        service.count(first.planNo(), new CountRequest(rid(), "GRD-A", "GRD-B", 50_000_00L));
        service.deviceConfirm(first.planNo(), new DeviceConfirmRequest(rid(), 50_000_00L));
        service.seal(first.planNo(), new SealRequest(rid()));

        PlanResponse second = prepare("ATM-002", "BOX-002-2", 50_000_00L);
        assertEquals("PREPARED", second.status());
    }

    // ---------- 台账与挂账链复算 ----------

    @Test
    void ledger_andOverShortChainAreRecomputable() {
        PlanResponse prepared = prepare("ATM-002", "BOX-002-1", 100_000_00L);
        service.approve(prepared.planNo(), new ApproveRequest(rid()));
        service.checkin(prepared.planNo(), new CheckinRequest(rid(), "GRD-A"));
        service.checkin(prepared.planNo(), new CheckinRequest(rid(), "GRD-B"));
        service.count(prepared.planNo(), new CountRequest(rid(), "GRD-A", "GRD-B", 100_000_00L));
        service.deviceConfirm(prepared.planNo(), new DeviceConfirmRequest(rid(), 100_000_00L));

        long osId = createOverShortPlan();

        List<PlanResponse> ledger = service.ledger();
        assertTrue(ledger.stream().anyMatch(x -> x.planNo().equals(prepared.planNo())));
        assertTrue(ledger.size() >= 2);

        List<OverShortResponse> chain = service.overShorts();
        assertEquals(1, chain.size());
        assertEquals(osId, chain.get(0).id());
        assertEquals(-5_000_00L, chain.get(0).amount());
        assertNotNull(chain.get(0).planNo());
    }

    // ---------- 辅助组装 ----------

    private PlanResponse happyToApproved(long amount) {
        PlanResponse p = prepare("ATM-001", "BOX-001-1", amount);
        return service.approve(p.planNo(), new ApproveRequest(rid()));
    }

    private PlanResponse happyToEscortDone(long amount) {
        PlanResponse p = happyToApproved(amount);
        service.checkin(p.planNo(), new CheckinRequest(rid(), "GRD-A"));
        service.checkin(p.planNo(), new CheckinRequest(rid(), "GRD-B"));
        return p;
    }

    private PlanResponse happyToReconciled(long amount) {
        PlanResponse p = happyToEscortDone(amount);
        service.count(p.planNo(), new CountRequest(rid(), "GRD-A", "GRD-B", amount));
        service.deviceConfirm(p.planNo(), new DeviceConfirmRequest(rid(), amount));
        return p;
    }

    /** 构造一笔 2026-01-10 发生、挂账 -5 分（95/100）的 OPEN 长短款。 */
    private long createOverShortPlan() {
        clock.setFixed(Instant.parse("2026-01-10T08:00:00Z"));
        PlanResponse p = prepare("ATM-001", "BOX-001-1", 100_000_00L);
        service.approve(p.planNo(), new ApproveRequest(rid()));
        service.checkin(p.planNo(), new CheckinRequest(rid(), "GRD-A"));
        service.checkin(p.planNo(), new CheckinRequest(rid(), "GRD-B"));
        service.count(p.planNo(), new CountRequest(rid(), "GRD-A", "GRD-B", 90_000_00L));
        PlanResponse confirmed = service.deviceConfirm(p.planNo(),
                new DeviceConfirmRequest(rid(), 95_000_00L));
        return confirmed.overShortId();
    }

    private String planNoOf(long overShortId) {
        long planId = jdbc.queryForObject(
                "SELECT plan_id FROM over_short WHERE id = ?", Long.class, overShortId);
        return planRepository.findById(planId).getPlanNo();
    }

    private long planCount() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM replenish_plan", Long.class);
    }
}
