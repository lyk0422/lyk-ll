package com.example.starter.service;

import com.example.starter.api.ApproveRequest;
import com.example.starter.api.CheckinRequest;
import com.example.starter.api.CreatePlanRequest;
import com.example.starter.api.DailyReconciliationResponse;
import com.example.starter.api.GenerateTemporaryRequest;
import com.example.starter.api.PlanResponse;
import com.example.starter.api.TemporaryGenerateResponse;
import com.example.starter.domain.PlanStatus;
import com.example.starter.domain.PlanType;
import com.example.starter.repo.Atm;
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
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 节假日临时加钞通道 H2 事务/并发/边界测试。覆盖：节前 3 日峰值预测生成、
 * 快速通道 2 小时押运时限恰满冻结、与在途常规计划冲突让路（金额大者优先、保留排队位置）、
 * 同一 ATM 执行中唯一、requestId 幂等与并发生成唯一受理。
 * 测试时钟 BASE=2026-09-20T08:00Z，合成节假日 2026-09-23，节前 3 日窗口含 09-20。
 */
@SpringBootTest(classes = {com.example.starter.StarterApplication.class, TestClockConfig.class})
class TemporaryPlanServiceTest {

    private static final LocalDate BIZ_DATE = LocalDate.parse("2026-09-20");

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

    private TemporaryGenerateResponse generate(String requestId) {
        return (TemporaryGenerateResponse) service.generateTemporaryPlans(
                new GenerateTemporaryRequest(requestId)).body();
    }

    private long tempPlanId(String atmCode) {
        Atm atm = repo.findAtmByCode(atmCode).orElseThrow();
        return repo.findPlanByAtmAndDate(atm.id(), BIZ_DATE, PlanType.TEMPORARY).orElseThrow().getId();
    }

    private long createRegular(String atmCode, long amount) {
        PlanResponse resp = (PlanResponse) service
                .createPlan(new CreatePlanRequest(rid(), atmCode, amount)).body();
        return resp.id();
    }

    // ==================== 峰值预测生成与快速通道 ====================

    @Test
    void pre_holiday_peak_forecast_generates_fast_approved_temporary_plans() {
        TemporaryGenerateResponse resp = generate(rid());
        assertTrue(resp.preHolidayWindow());
        assertEquals(3, resp.generatedCount(), "三个网点预测均为日均 200%，超出 150% 阈值");

        PlanRow temp = repo.findPlanById(tempPlanId("ATM-0001")).orElseThrow();
        assertEquals(PlanType.TEMPORARY, temp.getPlanType());
        assertEquals(300000L, temp.getPredictedAmount(), "合成峰值系数 200%：150000*2");
        assertEquals(300000L, temp.getPlannedAmount());
        assertEquals(PlanStatus.APPROVED, temp.getStatus(), "快速通道生成即批准，免常规逐级审批");
        assertEquals(Instant.parse("2026-09-20T08:00:00Z"), temp.getApprovedAt());
        assertEquals(Instant.parse("2026-09-20T10:00:00Z"), temp.getEscortDeadline(),
                "临时计划押运时限压缩为 2 小时");

        // 计划加钞额封顶钞箱容量（ATM-0003 预测 160000 未超容量 200000；ATM-0002 预测 440000）
        PlanRow temp3 = repo.findPlanById(tempPlanId("ATM-0003")).orElseThrow();
        assertEquals(160000L, temp3.getPlannedAmount());

        // 临时计划不占用常规审批队列：非 DRAFT，常规审批入口拒绝
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.approve(temp.getId(), new ApproveRequest(rid())));
        assertEquals(409, ex.getStatusCode());

        // 快速通道结论留痕并纳入日结核对
        DailyReconciliationResponse daily = service.dailyReconciliation(BIZ_DATE);
        assertEquals(3, daily.fastTrackDecisionCount());
        assertTrue(daily.fastTrackDecisions().stream()
                .allMatch(d -> "AUTO_APPROVED".equals(d.decision())));
    }

    @Test
    void outside_pre_holiday_window_generates_nothing() {
        clock.setInstant(Instant.parse("2026-09-25T08:00:00Z")); // 距 09-23 已过、距 10-01 超过 3 日
        TemporaryGenerateResponse resp = generate(rid());
        assertEquals(false, resp.preHolidayWindow());
        assertEquals(0, resp.generatedCount());
        assertEquals(0L, repo.findAllPlans().size());
    }

    @Test
    void generate_is_idempotent_by_request_id_and_conflicts_on_different_action() {
        String requestId = rid();
        TemporaryGenerateResponse first = generate(requestId);
        TemporaryGenerateResponse replay = generate(requestId);
        assertEquals(first.generatedCount(), replay.generatedCount(), "同键同参返回首次结果");
        assertEquals(3L, repo.findAllPlans().size(), "重放不重复生成");

        // 同一 requestId 用于不同业务动作：指纹不同，409
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.createPlan(new CreatePlanRequest(requestId, "ATM-0001", 1000L)));
        assertEquals(409, ex.getStatusCode());

        // 不同 requestId 当日重复生成：按日期维度幂等，不重复建单
        TemporaryGenerateResponse again = generate(rid());
        assertEquals(0, again.generatedCount());
        assertEquals(3L, repo.findAllPlans().size());
    }

    // ==================== 恰满 2 小时冻结 ====================

    @Test
    void exactly_two_hours_without_checkin_marks_abnormal_and_freezes_atm() {
        generate(rid());
        long tempId = tempPlanId("ATM-0001");

        // 截止 10:00；09:59:59 第一名打卡仍有效
        clock.setInstant(Instant.parse("2026-09-20T09:59:59Z"));
        service.checkin(tempId, new CheckinRequest(rid(), "C-01"));

        // 恰满 2 小时：第二名打卡不再受理，自动转押运异常并冻结，响应 409
        clock.setInstant(Instant.parse("2026-09-20T10:00:00Z"));
        var late = service.checkin(tempId, new CheckinRequest(rid(), "C-02"));
        assertEquals(409, late.statusCode());
        assertEquals(PlanStatus.ESCORT_ABNORMAL, repo.findPlanById(tempId).orElseThrow().getStatus());
        assertTrue(repo.findAtmByCode("ATM-0001").orElseThrow().frozen(),
                "恰满 2 小时未完成打卡应冻结该 ATM 后续计划");

        BusinessException blocked = assertThrows(BusinessException.class,
                () -> service.createPlan(new CreatePlanRequest(rid(), "ATM-0001", null)));
        assertEquals(409, blocked.getStatusCode());
    }

    @Test
    void sweep_marks_temp_plan_abnormal_at_exact_two_hour_deadline() {
        generate(rid());
        long tempId = tempPlanId("ATM-0002");

        clock.setInstant(Instant.parse("2026-09-20T09:59:59Z"));
        assertEquals(0, service.sweepTimeouts(), "恰满前 1 秒不应异常");
        clock.setInstant(Instant.parse("2026-09-20T10:00:00Z"));
        assertEquals(3, service.sweepTimeouts(), "恰满 2 小时应扫描转异常（三个网点临时单同时到期）");
        assertEquals(PlanStatus.ESCORT_ABNORMAL, repo.findPlanById(tempId).orElseThrow().getStatus());
        assertTrue(repo.findAtmByCode("ATM-0002").orElseThrow().frozen());
        assertEquals(0, service.sweepTimeouts(), "扫描幂等");
    }

    // ==================== 冲突裁决：金额大者优先，让路保留排队位置 ====================

    @Test
    void temporary_with_larger_amount_preempts_inflight_regular_and_keeps_queue_position() {
        long regularId = createRegular("ATM-0001", 100000L);
        service.approve(regularId, new ApproveRequest(rid()));
        long regularQueueSeq = repo.findPlanById(regularId).orElseThrow().getQueueSeq();

        generate(rid());

        PlanRow regular = repo.findPlanById(regularId).orElseThrow();
        assertEquals(PlanStatus.YIELDED, regular.getStatus(), "金额小的在途常规计划应让路");
        assertEquals(regularQueueSeq, regular.getQueueSeq(), "被让路计划保留排队位置");
        assertEquals(null, regular.getEscortDeadline(), "让路后押运时限失效");

        PlanRow temp = repo.findPlanById(tempPlanId("ATM-0001")).orElseThrow();
        assertEquals(PlanStatus.APPROVED, temp.getStatus());
        assertEquals(300000L, temp.getPlannedAmount());
        assertTrue(temp.getQueueSeq() > regularQueueSeq, "临时计划排入队尾");

        // 冲突裁决留痕：让路 + 优先各一条
        var decisions = service.dailyReconciliation(BIZ_DATE).fastTrackDecisions();
        assertTrue(decisions.stream().anyMatch(d -> "REGULAR_YIELDED".equals(d.decision())
                && d.planId() == regularId));
        assertTrue(decisions.stream().anyMatch(d -> "PREEMPTED_REGULAR".equals(d.decision())
                && d.planId() == temp.getId()));

        // 执行中唯一：让路单在临时单执行期间不得重新审批
        BusinessException conflict = assertThrows(BusinessException.class,
                () -> service.approve(regularId, new ApproveRequest(rid())));
        assertEquals(409, conflict.getStatusCode());

        // 临时单双人押运完成后，让路单可重新审批放行，排队位置不变
        service.checkin(temp.getId(), new CheckinRequest(rid(), "C-01"));
        service.checkin(temp.getId(), new CheckinRequest(rid(), "C-02"));
        PlanResponse reapproved = (PlanResponse) service.approve(regularId, new ApproveRequest(rid())).body();
        assertEquals(PlanStatus.APPROVED.name(), reapproved.status());
        assertEquals(regularQueueSeq, repo.findPlanById(regularId).orElseThrow().getQueueSeq());
        assertNotNull(repo.findPlanById(regularId).orElseThrow().getEscortDeadline());
    }

    @Test
    void temporary_yields_to_inflight_regular_with_larger_or_equal_amount() {
        // 在途常规单金额更大：ATM-0002 常规 500000 > 临时预测 440000
        long bigRegular = createRegular("ATM-0002", 500000L);
        service.approve(bigRegular, new ApproveRequest(rid()));
        // 在途常规单金额恰等：ATM-0001 常规 300000 == 临时预测 300000
        long equalRegular = createRegular("ATM-0001", 300000L);
        service.approve(equalRegular, new ApproveRequest(rid()));

        generate(rid());

        PlanRow temp2 = repo.findPlanById(tempPlanId("ATM-0002")).orElseThrow();
        assertEquals(PlanStatus.YIELDED, temp2.getStatus(), "金额小于在途单，临时计划让路");
        assertTrue(temp2.getQueueSeq() > 0, "让路临时计划保留排队位置");
        assertEquals(PlanStatus.APPROVED, repo.findPlanById(bigRegular).orElseThrow().getStatus(),
                "在途大单继续执行");

        PlanRow temp1 = repo.findPlanById(tempPlanId("ATM-0001")).orElseThrow();
        assertEquals(PlanStatus.YIELDED, temp1.getStatus(), "恰等金额在途单优先，临时计划让路");

        var decisions = service.dailyReconciliation(BIZ_DATE).fastTrackDecisions();
        assertEquals(2, decisions.stream().filter(d -> "YIELDED_TO_REGULAR".equals(d.decision())).count());
        // ATM-0003 无在途单，正常快速批准
        assertEquals(PlanStatus.APPROVED, repo.findPlanById(tempPlanId("ATM-0003")).orElseThrow().getStatus());
    }

    // ==================== 同一 ATM 执行中唯一 ====================

    @Test
    void same_atm_allows_only_one_executing_plan() {
        long first = createRegular("ATM-0001", 100000L);
        service.approve(first, new ApproveRequest(rid()));

        clock.setInstant(Instant.parse("2026-09-21T08:00:00Z")); // 次日再编一张常规单
        long second = createRegular("ATM-0001", 120000L);
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.approve(second, new ApproveRequest(rid())));
        assertEquals(409, ex.getStatusCode(), "存在执行中单时第二单不得批准");
        assertEquals(PlanStatus.DRAFT, repo.findPlanById(second).orElseThrow().getStatus());
    }

    // ==================== 并发生成唯一受理 ====================

    @Test
    void concurrent_generate_creates_only_one_temp_plan_per_atm() throws Exception {
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger success = new AtomicInteger();
        AtomicInteger failed = new AtomicInteger();
        try {
            for (int i = 0; i < threads; i++) {
                pool.submit(() -> {
                    try {
                        start.await();
                        generate(rid());
                        success.incrementAndGet();
                    } catch (Exception e) {
                        failed.incrementAndGet();
                    }
                });
            }
            start.countDown();
        } finally {
            pool.shutdown();
            assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));
        }
        assertEquals(threads, success.get(), "并发生成均正常受理（日期维度幂等，不报错）");
        assertEquals(0, failed.get());
        assertEquals(3L, repo.findAllPlans().stream()
                .filter(p -> p.getPlanType() == PlanType.TEMPORARY).count(),
                "每个网点当日只生成一张临时计划");
        // 快速通道留痕与生成结果一致：每网点一条 AUTO_APPROVED
        assertEquals(3, service.dailyReconciliation(BIZ_DATE).fastTrackDecisionCount());
    }
}
