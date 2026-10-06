package com.example.starter.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.example.starter.AbstractIntegrationTest;
import com.example.starter.TestInfraConfiguration;
import com.example.starter.domain.Interval;
import com.example.starter.domain.PlanStatus;
import com.example.starter.domain.ResourceType;
import com.example.starter.error.BusinessException;
import com.example.starter.web.dto.PlanResponse;

/**
 * 改期集成测试：改期须重新校验引航员工时与拖轮占用，结论与重新锁定在同一事务内完成。
 */
class PlanRescheduleTest extends AbstractIntegrationTest {

    private static final LocalDateTime BASE = TestInfraConfiguration.BASE_TIME;

    @Autowired
    private PlanService planService;

    @Autowired
    private IdempotencyService idempotencyService;

    private PlanResponse confirmedPlan(String planNo, long pilotId, List<Long> tugs, int startHour, int endHour) {
        PlanResponse plan = planService.apply(planNo, "合成货轮-" + planNo, "V001");
        planService.arrange(plan.id(), pilotId, tugs, BASE.plusHours(startHour), BASE.plusHours(endHour));
        return planService.confirm(plan.id());
    }

    @Test
    void 改期与拖轮占用冲突时拒绝且原锁定不变() {
        PlanResponse plan1 = confirmedPlan("PLAN-RS-1", 1L, List.of(1L), 10, 12);
        confirmedPlan("PLAN-RS-2", 2L, List.of(1L), 14, 16);

        BusinessException e = assertThrows(BusinessException.class, () -> planService.reschedule(
                plan1.id(), BASE.plusHours(15), BASE.plusHours(17)));
        assertEquals("TUG_OCCUPIED", e.code());

        PlanResponse after = planService.get(plan1.id());
        assertEquals(PlanStatus.CONFIRMED.name(), after.status(), "改期失败状态应保持确认态");
        assertEquals(BASE.plusHours(10), after.tideStart(), "改期失败窗口应回滚保持原值");
        assertEquals(BASE.plusHours(12), after.tideEnd());
        List<Interval> pilotWindows = lockRepository.findActiveWindows(ResourceType.PILOT, 1L, null);
        assertEquals(List.of(new Interval(BASE.plusHours(10), BASE.plusHours(12))), pilotWindows,
                "改期失败原锁定应保持不变");
    }

    @Test
    void 改期成功后旧锁释放新锁生效() {
        PlanResponse plan1 = confirmedPlan("PLAN-RS-3", 1L, List.of(1L), 10, 12);
        confirmedPlan("PLAN-RS-4", 2L, List.of(1L), 14, 16);

        // 改期到 12:00-13:00：与原窗口端点相接、与他人窗口不重叠
        PlanResponse rescheduled = planService.reschedule(plan1.id(), BASE.plusHours(12), BASE.plusHours(13));
        assertEquals(BASE.plusHours(12), rescheduled.tideStart());
        assertEquals(BASE.plusHours(13), rescheduled.tideEnd());
        assertEquals(PlanStatus.CONFIRMED.name(), rescheduled.status());

        assertEquals(2, lockRepository.countActiveLocksByPlan(plan1.id()), "改期后仍为引航员+拖轮两条锁");
        List<Interval> pilotWindows = lockRepository.findActiveWindows(ResourceType.PILOT, 1L, null);
        assertEquals(List.of(new Interval(BASE.plusHours(12), BASE.plusHours(13))), pilotWindows,
                "生效锁定应为新窗口");
    }

    @Test
    void 改期触发引航员工时重新校验() {
        PlanResponse plan1 = confirmedPlan("PLAN-RS-5", 1L, List.of(1L), 8, 12);
        PlanResponse plan2 = confirmedPlan("PLAN-RS-6", 1L, List.of(2L), 13, 14);

        // 改期到 12:30-13:30：与 08:00-12:00 间隔仅 30 分钟，连续段累计 5 小时，违规
        BusinessException e = assertThrows(BusinessException.class, () -> planService.reschedule(
                plan2.id(), BASE.plusHours(12).plusMinutes(30), BASE.plusHours(13).plusMinutes(30)));
        assertEquals("WORK_HOUR_VIOLATION", e.code());
        assertEquals(BASE.plusHours(13), planService.get(plan2.id()).tideStart(), "改期失败窗口保持原值");
        assertEquals(PlanStatus.CONFIRMED.name(), planService.get(plan1.id()).status());
    }

    @Test
    void 非确认状态不允许改期() {
        PlanResponse plan = planService.apply("PLAN-RS-7", "合成货轮", "V001");
        planService.arrange(plan.id(), 1L, List.of(1L), BASE.plusHours(10), BASE.plusHours(12));
        BusinessException e = assertThrows(BusinessException.class, () -> planService.reschedule(
                plan.id(), BASE.plusHours(12), BASE.plusHours(13)));
        assertEquals("INVALID_STATE", e.code());
    }

    @Test
    void 幂等重放返回首次结果且同键异参409() {
        PlanResponse first = idempotentApply("req-replay-1", "PLAN-RP-1", "合成货轮", "V001");
        PlanResponse replayed = idempotentApply("req-replay-1", "PLAN-RP-1", "合成货轮", "V001");
        assertEquals(first.id(), replayed.id(), "同键同参应返回首次结果");
        assertEquals(1, planRepository.findAll().size(), "重放不得重复落库");

        BusinessException e = assertThrows(BusinessException.class,
                () -> idempotentApply("req-replay-1", "PLAN-RP-1", "另一艘船", "V001"));
        assertEquals(409, e.status().value());
        assertEquals("IDEMPOTENCY_CONFLICT", e.code());
        assertTrue(e.getMessage().contains("req-replay-1"));
    }

    private PlanResponse idempotentApply(String requestId, String planNo, String shipName, String voyageNo) {
        return idempotencyService.execute("APPLY", requestId, List.of(planNo, shipName, voyageNo),
                () -> planService.apply(planNo, shipName, voyageNo));
    }
}
