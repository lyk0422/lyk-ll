package com.example.starter.service;

import com.example.starter.api.ApproveRequest;
import com.example.starter.api.CheckinRequest;
import com.example.starter.api.CountRequest;
import com.example.starter.api.CreatePlanRequest;
import com.example.starter.api.DailyReconciliationResponse;
import com.example.starter.api.DeviceConfirmRequest;
import com.example.starter.api.FastTrackAuditResponse;
import com.example.starter.api.ForecastGenerateRequest;
import com.example.starter.api.ForecastResponse;
import com.example.starter.api.PlanResponse;
import com.example.starter.api.ResumeRequest;
import com.example.starter.api.SealRequest;
import com.example.starter.domain.PlanStatus;
import com.example.starter.domain.PlanType;
import com.example.starter.repo.PlanRow;
import com.example.starter.repo.ReplenishRepository;
import com.example.starter.testsupport.MutableClock;
import com.example.starter.testsupport.TestClockConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 节假日临时加钞计划 H2 事务/并发/边界测试：峰值预测生成、快速通道 2 小时时限、
 * 冲突让路裁决、排队恢复、幂等与日结核对。独立内存库 atm_test 与可控 UTC 时钟。
 */
@SpringBootTest(classes = {com.example.starter.StarterApplication.class, TestClockConfig.class})
class TempPlanFlowTest {

    /** 合成节假日：2026-09-23 秋分购物节，峰值系数 2.0 倍；节前 3 日窗口 09-20 ~ 09-22。 */
    private static final LocalDate HOLIDAY_PEAK = LocalDate.parse("2026-09-23");
    /** 合成节假日：2026-09-25 普通周末，峰值系数 1.2 倍（不超阈值）。 */
    private static final LocalDate HOLIDAY_CALM = LocalDate.parse("2026-09-25");
    /** 合成节假日：2026-09-26 持平纪念日，峰值系数恰 1.5 倍（恰等阈值不触发）。 */
    private static final LocalDate HOLIDAY_EVEN = LocalDate.parse("2026-09-26");
    /** 合成节假日：2026-10-01 国庆节，峰值系数 2.5 倍；窗口 09-28 ~ 09-30。 */
    private static final LocalDate HOLIDAY_NATIONAL = LocalDate.parse("2026-10-01");

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
        jdbc.update("DELETE FROM fast_track_audit");
        jdbc.update("DELETE FROM plan_discrepancy");
        jdbc.update("DELETE FROM escort_checkin");
        jdbc.update("DELETE FROM replenish_plan");
        jdbc.update("UPDATE atm SET frozen = 0");
        jdbc.update("UPDATE custodian SET frozen = 0");
    }

    private static String rid() {
        return UUID.randomUUID().toString();
    }

    private ForecastResponse generate(LocalDate holiday) {
        return (ForecastResponse) service.generateTemporaryPlans(
                new ForecastGenerateRequest(rid(), holiday)).body();
    }

    private ForecastResponse.Item itemFor(ForecastResponse resp, String atmCode) {
        return resp.results().stream().filter(i -> i.atmCode().equals(atmCode)).findFirst().orElseThrow();
    }

    private long createRegularPlan(String atmCode, Long amount) {
        PlanResponse resp = (PlanResponse) service.createPlan(new CreatePlanRequest(rid(), atmCode, amount)).body();
        return resp.id();
    }

    // ==================== 峰值预测生成（主流程） ====================

    @Test
    void forecast_generates_temporary_plans_for_atms_over_threshold() {
        ForecastResponse resp = generate(HOLIDAY_PEAK);
        assertEquals(HOLIDAY_PEAK, resp.holidayDate());
        assertEquals(LocalDate.parse("2026-09-20"), resp.windowStart());
        assertEquals(LocalDate.parse("2026-09-22"), resp.windowEnd());
        assertEquals(3, resp.results().size());

        // ATM-0001：日均 150000 × 2.0 = 300000 > 阈值 225000，容量 400000 内
        ForecastResponse.Item a1 = itemFor(resp, "ATM-0001");
        assertTrue(a1.generated());
        assertEquals(300000L, a1.predictedPeak());
        assertEquals(225000L, a1.threshold());
        assertEquals(PlanStatus.APPROVED.name(), a1.planStatus(), "快速通道免逐级审批，生成即放行");

        PlanRow temp = repo.findPlanById(a1.planId()).orElseThrow();
        assertEquals(PlanType.TEMPORARY, temp.getPlanType());
        assertEquals(300000L, temp.getPlannedAmount());
        // 押运时限压缩为 2 小时（非常规 4 小时）
        assertEquals(Instant.parse("2026-09-20T10:00:00Z"), temp.getEscortDeadline());
        assertNotNull(temp.getApprovedAt());

        // ATM-0003：日均 80000 × 2.0 = 160000，容量 200000 内
        ForecastResponse.Item a3 = itemFor(resp, "ATM-0003");
        assertTrue(a3.generated());
        assertEquals(160000L, repo.findPlanById(a3.planId()).orElseThrow().getPlannedAmount());

        // 快速通道结论留痕：每个生成的临时计划一条 AUTO_APPROVED
        List<FastTrackAuditResponse> audits = service.fastTrackAudits(a1.planId());
        assertEquals(1, audits.size());
        assertEquals("AUTO_APPROVED", audits.get(0).decision());

        // 临时计划不占常规审批队列：状态直接 APPROVED，从不出现 DRAFT
        assertEquals(0L, repo.findAllPlans().stream()
                .filter(p -> p.getPlanType() == PlanType.TEMPORARY && p.getStatus() == PlanStatus.DRAFT).count());
    }

    @Test
    void forecast_planned_amount_capped_at_capacity_and_equal_capacity_valid() {
        clock.setInstant(Instant.parse("2026-09-28T08:00:00Z")); // 国庆节前 3 日窗口
        ForecastResponse resp = generate(HOLIDAY_NATIONAL);
        // ATM-0003：日均 80000 × 2.5 = 200000，恰等容量合法
        ForecastResponse.Item a3 = itemFor(resp, "ATM-0003");
        assertTrue(a3.generated());
        assertEquals(200000L, repo.findPlanById(a3.planId()).orElseThrow().getPlannedAmount());
    }

    // ==================== 阈值与窗口边界 ====================

    @Test
    void forecast_below_threshold_generates_nothing() {
        clock.setInstant(Instant.parse("2026-09-22T08:00:00Z")); // 普通周末节前 3 日窗口
        ForecastResponse resp = generate(HOLIDAY_CALM);
        assertEquals(3, resp.results().size());
        assertTrue(resp.results().stream().noneMatch(ForecastResponse.Item::generated));
        assertEquals(180000L, itemFor(resp, "ATM-0001").predictedPeak(), "150000 × 1.2");
        assertEquals(0L, repo.findAllPlans().size());
    }

    @Test
    void forecast_exactly_at_threshold_generates_nothing() {
        clock.setInstant(Instant.parse("2026-09-23T08:00:00Z")); // 持平纪念日前 3 日窗口
        ForecastResponse resp = generate(HOLIDAY_EVEN);
        // 恰 150%：225000 == 阈值 225000，不超出，不生成
        ForecastResponse.Item a1 = itemFor(resp, "ATM-0001");
        assertEquals(225000L, a1.predictedPeak());
        assertEquals(225000L, a1.threshold());
        assertFalse(a1.generated());
        assertEquals(0L, repo.findAllPlans().size());
    }

    @Test
    void forecast_outside_window_rejected_and_unknown_holiday_404() {
        // 国庆节窗口为 09-28 ~ 09-30，当前 09-20 不在窗口内
        BusinessException outOfWindow = assertThrows(BusinessException.class,
                () -> service.generateTemporaryPlans(new ForecastGenerateRequest(rid(), HOLIDAY_NATIONAL)));
        assertEquals(409, outOfWindow.getStatusCode());

        BusinessException unknown = assertThrows(BusinessException.class,
                () -> service.generateTemporaryPlans(
                        new ForecastGenerateRequest(rid(), LocalDate.parse("2026-12-25"))));
        assertEquals(404, unknown.getStatusCode());
    }

    @Test
    void forecast_skips_frozen_atm() {
        repo.freezeAtm(repo.findAtmByCode("ATM-0001").orElseThrow().id());
        ForecastResponse resp = generate(HOLIDAY_PEAK);
        assertFalse(itemFor(resp, "ATM-0001").generated());
        assertTrue(itemFor(resp, "ATM-0002").generated());
        assertEquals(2L, repo.findAllPlans().size());
    }

    // ==================== 恰满 2 小时冻结 ====================

    @Test
    void exactly_two_hours_without_second_checkin_marks_abnormal_and_freezes_atm() {
        ForecastResponse resp = generate(HOLIDAY_PEAK);
        long tempId = itemFor(resp, "ATM-0001").planId();

        // 08:00 放行，时限 10:00；09:59:59 第一名打卡仍有效
        clock.setInstant(Instant.parse("2026-09-20T09:59:59Z"));
        PlanResponse first = (PlanResponse) service.checkin(tempId, new CheckinRequest(rid(), "C-01")).body();
        assertEquals(1, first.checkinCount());

        // 恰满 2 小时：第二名打卡不再受理，自动转异常并冻结，响应 409
        clock.setInstant(Instant.parse("2026-09-20T10:00:00Z"));
        var late = service.checkin(tempId, new CheckinRequest(rid(), "C-02"));
        assertEquals(409, late.statusCode());
        assertEquals(PlanStatus.ESCORT_ABNORMAL, repo.findPlanById(tempId).orElseThrow().getStatus());
        assertTrue(repo.findAtmByCode("ATM-0001").orElseThrow().frozen(), "恰满 2 小时应冻结该 ATM 后续计划");

        // 冻结后该 ATM 不受理新的常规/临时计划
        BusinessException blocked = assertThrows(BusinessException.class,
                () -> service.createPlan(new CreatePlanRequest(rid(), "ATM-0001", null)));
        assertEquals(409, blocked.getStatusCode());
        clock.setInstant(Instant.parse("2026-09-21T08:00:00Z"));
        ForecastResponse next = generate(HOLIDAY_PEAK);
        assertFalse(itemFor(next, "ATM-0001").generated(), "冻结 ATM 不再生成临时计划");
    }

    @Test
    void sweep_marks_temporary_plan_abnormal_at_exact_two_hour_deadline() {
        ForecastResponse resp = generate(HOLIDAY_PEAK);
        long tempId = itemFor(resp, "ATM-0002").planId();

        clock.setInstant(Instant.parse("2026-09-20T09:59:59Z"));
        assertEquals(0, service.sweepTimeouts(), "恰满前 1 秒不应异常");
        clock.setInstant(Instant.parse("2026-09-20T10:00:00Z"));
        assertEquals(3, service.sweepTimeouts(), "恰满 2 小时应扫描转异常（3 张临时计划同时到期）");
        assertEquals(PlanStatus.ESCORT_ABNORMAL, repo.findPlanById(tempId).orElseThrow().getStatus());
        assertTrue(repo.findAtmByCode("ATM-0002").orElseThrow().frozen());
        assertEquals(0, service.sweepTimeouts(), "扫描幂等");
    }

    // ==================== 冲突让路裁决 ====================

    @Test
    void temporary_plan_wins_conflict_when_amount_larger_and_inflight_yields() {
        // 在途常规计划 100000（执行中），临时计划预测 300000 优先
        long regularId = createRegularPlan("ATM-0001", 100000L);
        service.approve(regularId, new ApproveRequest(rid()));

        ForecastResponse resp = generate(HOLIDAY_PEAK);
        ForecastResponse.Item item = itemFor(resp, "ATM-0001");
        assertTrue(item.generated());
        assertEquals(PlanStatus.APPROVED.name(), item.planStatus());

        // 裁决与状态变更同事务可见：在途计划让路，保留排队位置
        PlanRow regular = repo.findPlanById(regularId).orElseThrow();
        assertEquals(PlanStatus.YIELDED, regular.getStatus());
        assertEquals(1, regular.getQueuePosition(), "被让路计划保留排队位置");
        PlanRow temp = repo.findPlanById(item.planId()).orElseThrow();
        assertEquals(2, temp.getQueuePosition());

        // 快速通道结论留痕：双方各一条
        assertEquals("CONFLICT_YIELD", service.fastTrackAudits(regularId).get(0).decision());
        assertEquals("CONFLICT_WIN", service.fastTrackAudits(item.planId()).get(0).decision());

        // 在途计划让路后，临时计划执行期间常规计划不可恢复
        BusinessException blocked = assertThrows(BusinessException.class,
                () -> service.resume(regularId, new ResumeRequest(rid())));
        assertEquals(409, blocked.getStatusCode());

        // 临时计划走完主流程后，常规计划按排队位置恢复，重新起算 4 小时时限
        service.checkin(item.planId(), new CheckinRequest(rid(), "C-01"));
        service.checkin(item.planId(), new CheckinRequest(rid(), "C-02"));
        service.count(item.planId(), new CountRequest(rid(), 300000L, "C-01"));
        service.deviceConfirm(item.planId(), new DeviceConfirmRequest(rid(), 300000L));

        clock.setInstant(Instant.parse("2026-09-20T12:30:00Z"));
        PlanResponse resumed = (PlanResponse) service.resume(regularId, new ResumeRequest(rid())).body();
        assertEquals(PlanStatus.APPROVED.name(), resumed.status());
        assertEquals(Instant.parse("2026-09-20T16:30:00Z"), resumed.escortDeadline(), "常规计划恢复后 4 小时时限");
    }

    @Test
    void inflight_plan_wins_conflict_when_amount_not_smaller_and_temporary_yields() {
        // 在途常规计划 350000 ≥ 临时预测 300000：临时计划让路
        long regularId = createRegularPlan("ATM-0001", 350000L);
        service.approve(regularId, new ApproveRequest(rid()));

        ForecastResponse resp = generate(HOLIDAY_PEAK);
        ForecastResponse.Item item = itemFor(resp, "ATM-0001");
        assertTrue(item.generated());
        assertEquals(PlanStatus.YIELDED.name(), item.planStatus());
        assertEquals(PlanStatus.APPROVED, repo.findPlanById(regularId).orElseThrow().getStatus(),
                "在途计划金额优先，保持执行中");
        assertEquals("CONFLICT_YIELD", service.fastTrackAudits(item.planId()).get(0).decision());

        // 在途计划完成后，让路临时计划恢复，重新起算 2 小时时限
        service.checkin(regularId, new CheckinRequest(rid(), "C-01"));
        service.checkin(regularId, new CheckinRequest(rid(), "C-02"));
        service.count(regularId, new CountRequest(rid(), 350000L, "C-01"));
        service.deviceConfirm(regularId, new DeviceConfirmRequest(rid(), 350000L));

        clock.setInstant(Instant.parse("2026-09-20T11:00:00Z"));
        PlanResponse resumed = (PlanResponse) service.resume(item.planId(), new ResumeRequest(rid())).body();
        assertEquals(PlanStatus.APPROVED.name(), resumed.status());
        assertEquals(Instant.parse("2026-09-20T13:00:00Z"), resumed.escortDeadline(), "临时计划恢复后 2 小时时限");
        assertEquals("RESUMED", service.fastTrackAudits(item.planId()).get(1).decision());
    }

    @Test
    void yielded_plans_resume_in_queue_order() {
        // 第 1 日：常规 400000 执行中（队列 1），临时 T1 让路（队列 2）
        long regularId = createRegularPlan("ATM-0001", 400000L);
        service.approve(regularId, new ApproveRequest(rid()));
        long t1 = itemFor(generate(HOLIDAY_PEAK), "ATM-0001").planId();
        assertEquals(PlanStatus.YIELDED, repo.findPlanById(t1).orElseThrow().getStatus());

        // 常规计划押运完成后进入第 2 日，再生成临时 T2 仍让路（队列 3）
        service.checkin(regularId, new CheckinRequest(rid(), "C-01"));
        service.checkin(regularId, new CheckinRequest(rid(), "C-02"));
        clock.setInstant(Instant.parse("2026-09-21T08:00:00Z"));
        long t2 = itemFor(generate(HOLIDAY_PEAK), "ATM-0001").planId();
        PlanRow t2Row = repo.findPlanById(t2).orElseThrow();
        assertEquals(PlanStatus.YIELDED, t2Row.getStatus());
        assertEquals(3, t2Row.getQueuePosition());

        // 常规计划核销完成，让路计划须按队列顺序恢复：T2 不可越过 T1
        service.count(regularId, new CountRequest(rid(), 400000L, "C-01"));
        service.deviceConfirm(regularId, new DeviceConfirmRequest(rid(), 400000L));
        BusinessException outOfOrder = assertThrows(BusinessException.class,
                () -> service.resume(t2, new ResumeRequest(rid())));
        assertEquals(409, outOfOrder.getStatusCode());

        // T1 恢复后执行中，T2 仍不可恢复（同一 ATM 同时刻只允许一张执行中单）
        PlanResponse resumed = (PlanResponse) service.resume(t1, new ResumeRequest(rid())).body();
        assertEquals(PlanStatus.APPROVED.name(), resumed.status());
        BusinessException stillBlocked = assertThrows(BusinessException.class,
                () -> service.resume(t2, new ResumeRequest(rid())));
        assertEquals(409, stillBlocked.getStatusCode());
    }

    @Test
    void approve_rejected_while_temporary_plan_executing() {
        ForecastResponse resp = generate(HOLIDAY_PEAK);
        assertEquals(PlanStatus.APPROVED.name(), itemFor(resp, "ATM-0001").planStatus());

        // 临时计划执行中：同 ATM 常规计划可编制（不占同一唯一键），但审批被拒
        long regularId = createRegularPlan("ATM-0001", 100000L);
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.approve(regularId, new ApproveRequest(rid())));
        assertEquals(409, ex.getStatusCode());
    }

    @Test
    void resume_rejects_non_yielded_plan() {
        long regularId = createRegularPlan("ATM-0001", 100000L);
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.resume(regularId, new ResumeRequest(rid())));
        assertEquals(409, ex.getStatusCode());
    }

    // ==================== 临时计划主流程与封存 ====================

    @Test
    void temporary_plan_full_flow_to_sealed() {
        ForecastResponse resp = generate(HOLIDAY_PEAK);
        long tempId = itemFor(resp, "ATM-0001").planId();

        service.checkin(tempId, new CheckinRequest(rid(), "C-01"));
        PlanResponse escorted = (PlanResponse) service.checkin(tempId, new CheckinRequest(rid(), "C-02")).body();
        assertEquals(PlanStatus.ESCORTED.name(), escorted.status());

        service.count(tempId, new CountRequest(rid(), 300000L, "C-01"));
        PlanResponse reconciled = (PlanResponse) service
                .deviceConfirm(tempId, new DeviceConfirmRequest(rid(), 300000L)).body();
        assertEquals(PlanStatus.RECONCILED.name(), reconciled.status());

        PlanResponse sealed = (PlanResponse) service.seal(tempId, new SealRequest(rid())).body();
        assertEquals(PlanStatus.SEALED.name(), sealed.status());
        assertEquals("TEMPORARY", sealed.planType());
    }

    // ==================== 幂等与并发 ====================

    @Test
    void forecast_idempotent_replay_returns_first_result_and_conflicts_on_different_params() {
        String requestId = rid();
        ForecastResponse first = (ForecastResponse) service.generateTemporaryPlans(
                new ForecastGenerateRequest(requestId, HOLIDAY_PEAK)).body();
        ForecastResponse replay = (ForecastResponse) service.generateTemporaryPlans(
                new ForecastGenerateRequest(requestId, HOLIDAY_PEAK)).body();
        assertEquals(itemFor(first, "ATM-0001").planId(), itemFor(replay, "ATM-0001").planId(),
                "同键同参返回首次结果，不重复生成");
        assertEquals(3L, repo.findAllPlans().size());

        BusinessException conflict = assertThrows(BusinessException.class,
                () -> service.generateTemporaryPlans(new ForecastGenerateRequest(requestId, HOLIDAY_CALM)));
        assertEquals(409, conflict.getStatusCode(), "同键异参 409");

        // 新 requestId 当日重复预测：不重复生成临时计划
        ForecastResponse again = generate(HOLIDAY_PEAK);
        assertFalse(itemFor(again, "ATM-0001").generated());
        assertEquals(3L, repo.findAllPlans().size());
    }

    @Test
    void concurrent_forecast_generates_single_temporary_plan_per_atm() throws Exception {
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger ok = new AtomicInteger();
        try {
            for (int i = 0; i < threads; i++) {
                pool.submit(() -> {
                    try {
                        start.await();
                        service.generateTemporaryPlans(new ForecastGenerateRequest(rid(), HOLIDAY_PEAK));
                        ok.incrementAndGet();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                });
            }
            start.countDown();
        } finally {
            pool.shutdown();
            assertTrue(pool.awaitTermination(20, TimeUnit.SECONDS));
        }
        assertEquals(threads, ok.get(), "并发预测请求均可受理，重复者按当日唯一约束跳过");
        List<PlanRow> temps = repo.findAllPlans().stream()
                .filter(p -> p.getPlanType() == PlanType.TEMPORARY).toList();
        assertEquals(3, temps.size(), "每个 ATM 当日只生成一张临时计划");
        long atm1Temps = temps.stream()
                .filter(p -> p.getAtmId() == repo.findAtmByCode("ATM-0001").orElseThrow().id()).count();
        assertEquals(1L, atm1Temps);
    }

    // ==================== 日结核对 ====================

    @Test
    void daily_reconciliation_includes_fast_track_audits() {
        ForecastResponse resp = generate(HOLIDAY_PEAK);
        long tempId = itemFor(resp, "ATM-0001").planId();

        DailyReconciliationResponse daily = service.dailyReconciliation(LocalDate.parse("2026-09-20"));
        assertEquals(3, daily.plans().size(), "当日计划台账含全部临时计划");
        assertEquals(3, daily.fastTrackAudits().size(), "快速通道结论留痕纳入日结核对");
        assertTrue(daily.fastTrackAudits().stream().allMatch(a -> a.decision().equals("AUTO_APPROVED")));
        assertTrue(daily.plans().stream().anyMatch(p -> p.id().equals(tempId)));

        // 非当日无留痕
        DailyReconciliationResponse empty = service.dailyReconciliation(LocalDate.parse("2026-09-21"));
        assertEquals(0, empty.plans().size());
        assertEquals(0, empty.fastTrackAudits().size());
    }
}
