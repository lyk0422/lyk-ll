package com.example.starter.service;

import com.example.starter.api.ApproveRequest;
import com.example.starter.api.CheckinRequest;
import com.example.starter.api.CountRequest;
import com.example.starter.api.CreatePlanRequest;
import com.example.starter.api.DeviceConfirmRequest;
import com.example.starter.api.PlanResponse;
import com.example.starter.api.SealRequest;
import com.example.starter.api.SettleDiscrepancyRequest;
import com.example.starter.domain.DiscrepancyStatus;
import com.example.starter.domain.PlanStatus;
import com.example.starter.repo.Atm;
import com.example.starter.repo.Custodian;
import com.example.starter.repo.DiscrepancyRow;
import com.example.starter.repo.PlanRow;
import com.example.starter.repo.ReplenishRepository;
import com.example.starter.testsupport.MutableClock;
import com.example.starter.testsupport.TestClockConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 加钞服务 H2 事务/并发/边界测试。使用独立内存库 atm_test 与可控 UTC 时钟，
 * 每个用例前清理业务数据，场景间无顺序依赖。
 */
@SpringBootTest(classes = {com.example.starter.StarterApplication.class, TestClockConfig.class})
class ReplenishServiceFlowTest {

    @Autowired
    private ReplenishService service;
    @Autowired
    private ReplenishRepository repo;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private MutableClock clock;

    @BeforeEach
    void clean() {
        clock.setInstant(TestClockConfig.BASE);
        jdbc.update("DELETE FROM idempotent_request");
        jdbc.update("DELETE FROM late_event");
        jdbc.update("DELETE FROM plan_discrepancy");
        jdbc.update("DELETE FROM escort_checkin");
        jdbc.update("DELETE FROM fast_track_decision");
        jdbc.update("DELETE FROM replenish_plan");
        jdbc.update("UPDATE atm SET frozen = 0");
        jdbc.update("UPDATE custodian SET frozen = 0");
    }

    private static String rid() {
        return UUID.randomUUID().toString();
    }

    private long createPlan(String atmCode, Long amount) {
        PlanResponse resp = (PlanResponse) service.createPlan(new CreatePlanRequest(rid(), atmCode, amount)).body();
        return resp.id();
    }

    private long happyPlanToEscorted(String atmCode, Long amount) {
        long id = createPlan(atmCode, amount);
        service.approve(id, new ApproveRequest(rid()));
        service.checkin(id, new CheckinRequest(rid(), "C-01"));
        service.checkin(id, new CheckinRequest(rid(), "C-02"));
        return id;
    }

    // ==================== 预测编制 / 容量边界 ====================

    @Test
    void predicted_amount_uses_daily_average_and_equal_capacity_is_valid() {
        // 不传金额：按历史日均取现 150000 预测
        PlanResponse predicted = (PlanResponse) service
                .createPlan(new CreatePlanRequest(rid(), "ATM-0001", null)).body();
        assertEquals(150000L, predicted.predictedAmount());
        assertEquals(150000L, predicted.plannedAmount());
        assertEquals(PlanStatus.DRAFT.name(), predicted.status());

        // 恰等容量合法
        PlanResponse equalCap = (PlanResponse) service
                .createPlan(new CreatePlanRequest(rid(), "ATM-0002", 600000L)).body();
        assertEquals(600000L, equalCap.plannedAmount());
    }

    @Test
    void amount_over_capacity_rejected() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.createPlan(new CreatePlanRequest(rid(), "ATM-0002", 600001L)));
        assertEquals(409, ex.getStatusCode());
        assertEquals(0L, repo.findAllPlans().size());
    }

    // ==================== 双人押运打卡 ====================

    @Test
    void escort_requires_two_distinct_custodians_and_rejects_repeat() {
        long id = createPlan("ATM-0001", null);
        service.approve(id, new ApproveRequest(rid()));

        PlanResponse first = (PlanResponse) service.checkin(id, new CheckinRequest(rid(), "C-01")).body();
        assertEquals(1, first.checkinCount());
        assertEquals(PlanStatus.APPROVED.name(), first.status(), "单人打卡不生效，押运未完成");

        // 同一人重复打卡拒绝
        BusinessException repeat = assertThrows(BusinessException.class,
                () -> service.checkin(id, new CheckinRequest(rid(), "C-01")));
        assertEquals(409, repeat.getStatusCode());

        PlanResponse second = (PlanResponse) service.checkin(id, new CheckinRequest(rid(), "C-02")).body();
        assertEquals(2, second.checkinCount());
        assertEquals(PlanStatus.ESCORTED.name(), second.status());
        assertNotNull(second.escortCompletedAt());
    }

    // ==================== 恰 4 小时押运异常（受理时即时判定 + 扫描） ====================

    @Test
    void exactly_four_hours_without_second_checkin_marks_abnormal_and_freezes_atm() {
        long id = createPlan("ATM-0001", null);
        service.approve(id, new ApproveRequest(rid()));
        // 审批于 08:00，截止 12:00；11:59:59 第一名打卡仍有效
        clock.setInstant(Instant.parse("2026-09-20T11:59:59Z"));
        service.checkin(id, new CheckinRequest(rid(), "C-01"));

        // 恰满 4 小时：第二名打卡不再受理，自动转异常并冻结，响应 409
        clock.setInstant(Instant.parse("2026-09-20T12:00:00Z"));
        var lateCheckin = service.checkin(id, new CheckinRequest(rid(), "C-02"));
        assertEquals(409, lateCheckin.statusCode());

        PlanRow plan = repo.findPlanById(id).orElseThrow();
        assertEquals(PlanStatus.ESCORT_ABNORMAL, plan.getStatus());
        Atm atm = repo.findAtmByCode("ATM-0001").orElseThrow();
        assertTrue(atm.frozen(), "押运异常应冻结该 ATM 后续计划");

        // 冻结后该 ATM 不受理新计划
        BusinessException blocked = assertThrows(BusinessException.class,
                () -> service.createPlan(new CreatePlanRequest(rid(), "ATM-0001", null)));
        assertEquals(409, blocked.getStatusCode());
    }

    @Test
    void sweep_marks_abnormal_at_exact_deadline_for_unchecked_plan() {
        long id = createPlan("ATM-0002", 100000L);
        service.approve(id, new ApproveRequest(rid()));

        clock.setInstant(Instant.parse("2026-09-20T11:59:59Z"));
        assertEquals(0, service.sweepTimeouts(), "恰满前 1 秒不应异常");
        clock.setInstant(Instant.parse("2026-09-20T12:00:00Z"));
        assertEquals(1, service.sweepTimeouts(), "恰满 4 小时应扫描转异常");
        assertEquals(PlanStatus.ESCORT_ABNORMAL, repo.findPlanById(id).orElseThrow().getStatus());
        assertTrue(repo.findAtmByCode("ATM-0002").orElseThrow().frozen());

        // 扫描幂等：再次扫描不重复计数
        assertEquals(0, service.sweepTimeouts());
    }

    // ==================== 三方一致核销 ====================

    @Test
    void three_way_equal_reconciles() {
        long id = happyPlanToEscorted("ATM-0001", 150000L);
        service.count(id, new CountRequest(rid(), 150000L, "C-01"));
        PlanResponse resp = (PlanResponse) service
                .deviceConfirm(id, new DeviceConfirmRequest(rid(), 150000L)).body();
        assertEquals(PlanStatus.RECONCILED.name(), resp.status());
        assertNotNull(resp.reconciledAt());
        assertNull(resp.discrepancyId(), "三方一致不应有挂账");
        var rec = service.reconciliation(id);
        assertEquals(150000L, rec.countedAmount(), "counted readback");
        assertEquals(150000L, rec.deviceAmount(), "device readback");
        assertTrue(rec.consistent());
    }

    // ==================== 长短款挂账原子性 ====================

    @Test
    void mismatch_records_discrepancy_atomically_with_plan_status() {
        long id = happyPlanToEscorted("ATM-0001", 150000L);
        service.count(id, new CountRequest(rid(), 149000L, "C-01")); // 清点短款 1000

        PlanResponse resp = (PlanResponse) service
                .deviceConfirm(id, new DeviceConfirmRequest(rid(), 149000L)).body();
        assertEquals(PlanStatus.DISCREPANCY.name(), resp.status());
        assertNotNull(resp.discrepancyId(), "挂账与状态推进同事务，不得核销完成而挂账未记");

        // 直接查库验证挂账链与计划在同一提交中可见
        DiscrepancyRow d = repo.findDiscrepancyByPlan(id).orElseThrow();
        assertEquals(-1000L, d.getAmount(), "挂账金额=实际清点数-计划数");
        assertEquals(DiscrepancyStatus.OPEN, d.getStatus());
        assertEquals(java.time.LocalDate.parse("2026-09-20"), d.getOccurredDate());
        assertEquals(java.time.LocalDate.parse("2026-09-23"), d.getDueDate(), "截止为发生日+3 个自然日");
        assertFalse(service.reconciliation(id).consistent());
    }

    @Test
    void device_amount_alone_differing_also_discrepancy() {
        long id = happyPlanToEscorted("ATM-0003", 80000L);
        service.count(id, new CountRequest(rid(), 80000L, "C-02"));
        PlanResponse resp = (PlanResponse) service
                .deviceConfirm(id, new DeviceConfirmRequest(rid(), 80500L)).body();
        assertEquals(PlanStatus.DISCREPANCY.name(), resp.status());
        assertEquals(0L, repo.findDiscrepancyByPlan(id).orElseThrow().getAmount(),
                "挂账金额按实际清点数-计划数；设备数不一致同样触发长短款");
    }

    // ==================== 第 3 日销账边界 / 逾期升级冻结 ====================

    @Test
    void settle_on_exactly_third_day_is_accepted() {
        long id = happyPlanToEscorted("ATM-0003", 80000L);
        service.count(id, new CountRequest(rid(), 79000L, "C-03"));
        service.deviceConfirm(id, new DeviceConfirmRequest(rid(), 79000L));

        // 恰第 3 个自然日（UTC 日界）提交受理
        clock.setInstant(Instant.parse("2026-09-23T23:59:59Z"));
        PlanResponse resp = (PlanResponse) service
                .settleDiscrepancy(id, new SettleDiscrepancyRequest(rid())).body();
        assertEquals(PlanStatus.RECONCILED.name(), resp.status());
        assertEquals(DiscrepancyStatus.SETTLED, repo.findDiscrepancyByPlan(id).orElseThrow().getStatus());
        assertFalse(repo.findCustodianByCode("C-03").orElseThrow().frozen());
    }

    @Test
    void settle_after_due_date_escalates_and_freezes_responsible_custodian() {
        long id = happyPlanToEscorted("ATM-0003", 80000L);
        service.count(id, new CountRequest(rid(), 79000L, "C-03"));
        service.deviceConfirm(id, new DeviceConfirmRequest(rid(), 79000L));

        // 第 4 日：逾期升级并冻结责任人，响应 409
        clock.setInstant(Instant.parse("2026-09-24T00:00:00Z"));
        var overdue = service.settleDiscrepancy(id, new SettleDiscrepancyRequest(rid()));
        assertEquals(409, overdue.statusCode());
        assertEquals(DiscrepancyStatus.ESCALATED, repo.findDiscrepancyByPlan(id).orElseThrow().getStatus());
        Custodian frozen = repo.findCustodianByCode("C-03").orElseThrow();
        assertTrue(frozen.frozen(), "逾期应冻结责任人新任务");

        // 冻结押运员不得再打卡
        long other = createPlan("ATM-0001", 10000L);
        service.approve(other, new ApproveRequest(rid()));
        BusinessException blocked = assertThrows(BusinessException.class,
                () -> service.checkin(other, new CheckinRequest(rid(), "C-03")));
        assertEquals(409, blocked.getStatusCode());
    }

    @Test
    void sweep_escalates_overdue_open_discrepancy() {
        long id = happyPlanToEscorted("ATM-0002", 100000L);
        service.count(id, new CountRequest(rid(), 99000L, "C-04"));
        service.deviceConfirm(id, new DeviceConfirmRequest(rid(), 99000L));

        clock.setInstant(Instant.parse("2026-09-23T23:59:59Z"));
        assertEquals(0, service.sweepTimeouts());
        clock.setInstant(Instant.parse("2026-09-24T00:00:00Z"));
        assertEquals(1, service.sweepTimeouts());
        assertEquals(DiscrepancyStatus.ESCALATED, repo.findDiscrepancyByPlan(id).orElseThrow().getStatus());
        assertTrue(repo.findCustodianByCode("C-04").orElseThrow().frozen());
    }

    // ==================== requestId 幂等 ====================

    @Test
    void same_request_id_and_params_returns_first_result() {
        String requestId = rid();
        PlanResponse first = (PlanResponse) service
                .createPlan(new CreatePlanRequest(requestId, "ATM-0001", 120000L)).body();
        PlanResponse replay = (PlanResponse) service
                .createPlan(new CreatePlanRequest(requestId, "ATM-0001", 120000L)).body();
        assertEquals(first.id(), replay.id(), "同键同参返回首次结果，不重复建单");
        assertEquals(1L, repo.findAllPlans().size());

        // 押运打卡同键重放也返回首次结果，不产生重复打卡
        long id = first.id();
        service.approve(id, new ApproveRequest(rid()));
        String checkinRid = rid();
        PlanResponse c1 = (PlanResponse) service.checkin(id, new CheckinRequest(checkinRid, "C-01")).body();
        PlanResponse c1Replay = (PlanResponse) service.checkin(id, new CheckinRequest(checkinRid, "C-01")).body();
        assertEquals(c1.checkinCount(), c1Replay.checkinCount());
        assertEquals(1, repo.findCheckins(id).size());
    }

    @Test
    void same_request_id_different_params_conflicts_409() {
        String requestId = rid();
        service.createPlan(new CreatePlanRequest(requestId, "ATM-0001", 120000L));
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.createPlan(new CreatePlanRequest(requestId, "ATM-0001", 130000L)));
        assertEquals(409, ex.getStatusCode());
    }

    // ==================== 并发唯一受理 ====================

    @Test
    void concurrent_plan_creation_for_same_atm_day_only_one_accepted() throws Exception {
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger success = new AtomicInteger();
        AtomicInteger conflict = new AtomicInteger();
        try {
            for (int i = 0; i < threads; i++) {
                pool.submit(() -> {
                    try {
                        start.await();
                        service.createPlan(new CreatePlanRequest(rid(), "ATM-0001", 100000L));
                        success.incrementAndGet();
                    } catch (BusinessException e) {
                        if (e.getStatusCode() == 409) {
                            conflict.incrementAndGet();
                        }
                    } catch (InterruptedException ignored) {
                        Thread.currentThread().interrupt();
                    }
                });
            }
            start.countDown();
        } finally {
            pool.shutdown();
            assertTrue(pool.awaitTermination(15, TimeUnit.SECONDS));
        }
        assertEquals(1, success.get(), "同一 ATM 当日并发编制只能唯一受理");
        assertEquals(threads - 1, conflict.get());
        assertEquals(1L, repo.findPlanByAtmAndDate(
                repo.findAtmByCode("ATM-0001").orElseThrow().id(),
                java.time.LocalDate.parse("2026-09-20"),
                com.example.starter.domain.PlanType.REGULAR).stream().count());
    }

    @Test
    void concurrent_checkins_from_distinct_custodians_both_accepted_and_ordered() throws Exception {
        long id = createPlan("ATM-0001", null);
        service.approve(id, new ApproveRequest(rid()));

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        Future<?> f1 = pool.submit(() -> {
            try {
                start.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException(e);
            }
            service.checkin(id, new CheckinRequest(rid(), "C-01"));
        });
        Future<?> f2 = pool.submit(() -> {
            try {
                start.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException(e);
            }
            service.checkin(id, new CheckinRequest(rid(), "C-02"));
        });
        start.countDown();
        f1.get(10, TimeUnit.SECONDS);
        f2.get(10, TimeUnit.SECONDS);
        pool.shutdown();

        var checkins = repo.findCheckins(id);
        assertEquals(2, checkins.size());
        assertEquals(1, checkins.get(0).seq());
        assertEquals(2, checkins.get(1).seq(), "双人打卡按时间定序");
        assertEquals(PlanStatus.ESCORTED, repo.findPlanById(id).orElseThrow().getStatus());
    }

    // ==================== 封存终态与迟到数据 ====================

    @Test
    void seal_is_terminal_and_late_data_only_records_late_events() {
        long id = happyPlanToEscorted("ATM-0001", 150000L);
        service.count(id, new CountRequest(rid(), 150000L, "C-01"));
        service.deviceConfirm(id, new DeviceConfirmRequest(rid(), 150000L));

        PlanResponse sealed = (PlanResponse) service.seal(id, new SealRequest(rid())).body();
        assertEquals(PlanStatus.SEALED.name(), sealed.status());
        assertNotNull(sealed.sealedAt());

        // 重复封存幂等成功
        String sealRid = rid();
        PlanResponse again1 = (PlanResponse) service.seal(id, new SealRequest(sealRid)).body();
        PlanResponse again2 = (PlanResponse) service.seal(id, new SealRequest(sealRid)).body();
        assertEquals(PlanStatus.SEALED.name(), again1.status());
        assertEquals(again1.sealedAt(), again2.sealedAt());

        // 封存后的清点/打卡为迟到数据：只记迟到事件，状态不变
        PlanResponse lateCount = (PlanResponse) service
                .count(id, new CountRequest(rid(), 1L, "C-02")).body();
        assertTrue(lateCount.late());
        assertEquals(PlanStatus.SEALED, repo.findPlanById(id).orElseThrow().getStatus());
        assertEquals(150000L, repo.findPlanById(id).orElseThrow().getCountedAmount(), "迟到数据不得覆盖已封存值");

        PlanResponse lateCheckin = (PlanResponse) service
                .checkin(id, new CheckinRequest(rid(), "C-03")).body();
        assertTrue(lateCheckin.late());
        assertEquals(2, repo.findCheckins(id).size(), "迟到打卡不得落打卡记录");
        assertEquals(2L, jdbc.queryForObject("SELECT COUNT(*) FROM late_event WHERE plan_id = ?",
                Long.class, id));
    }
}
