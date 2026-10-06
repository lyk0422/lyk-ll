package com.example.starter.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;

import com.example.starter.AbstractIntegrationTest;
import com.example.starter.TestInfraConfiguration;
import com.example.starter.domain.PlanStatus;
import com.example.starter.error.BusinessException;
import com.example.starter.web.dto.PlanResponse;

/**
 * 引航计划业务规则集成测试（真实 H2）：状态机流转、半开窗口冲突、工时校验、
 * 确认回滚原子性、迟到取消边界、执行中唯一约束。
 */
class PlanServiceRuleTest extends AbstractIntegrationTest {

    private static final LocalDateTime BASE = TestInfraConfiguration.BASE_TIME;

    @Autowired
    private PlanService planService;

    private PlanResponse confirmedPlan(String planNo, long pilotId, List<Long> tugs,
            LocalDateTime start, LocalDateTime end) {
        PlanResponse plan = planService.apply(planNo, "合成货轮-" + planNo, "V001");
        planService.arrange(plan.id(), pilotId, tugs, start, end);
        return planService.confirm(plan.id());
    }

    @Test
    void 主流程申请到核销状态流转与锁定释放() {
        PlanResponse plan = planService.apply("PLAN-FLOW-1", "合成货轮", "V001");
        assertEquals(PlanStatus.APPLIED.name(), plan.status());

        PlanResponse arranged = planService.arrange(plan.id(), 1L, List.of(1L, 2L),
                BASE.plusHours(8), BASE.plusHours(10));
        assertEquals(PlanStatus.ARRANGED.name(), arranged.status());

        PlanResponse confirmed = planService.confirm(plan.id());
        assertEquals(PlanStatus.CONFIRMED.name(), confirmed.status());
        assertEquals(3, lockRepository.countActiveLocksByPlan(plan.id()), "确认后引航员+2拖轮共3条锁");

        PlanResponse executing = planService.start(plan.id());
        assertEquals(PlanStatus.EXECUTING.name(), executing.status());
        assertEquals(1, lockRepository.countExecutingLocks());

        PlanResponse settled = planService.settle(plan.id());
        assertEquals(PlanStatus.SETTLED.name(), settled.status());
        assertEquals(0, lockRepository.countActiveLocksByPlan(plan.id()), "核销后锁定全部释放");
        assertEquals(0, lockRepository.countExecutingLocks());
    }

    @Test
    void 潮水窗口端点相接不冲突() {
        confirmedPlan("PLAN-TOUCH-1", 1L, List.of(1L), BASE.plusHours(10), BASE.plusHours(12));
        PlanResponse plan2 = planService.apply("PLAN-TOUCH-2", "合成货轮", "V002");
        // 12:00 相接 10:00-12:00 的结束端点，半开区间不冲突
        PlanResponse arranged = planService.arrange(plan2.id(), 2L, List.of(1L),
                BASE.plusHours(12), BASE.plusHours(14));
        assertEquals(PlanStatus.ARRANGED.name(), arranged.status());
        planService.confirm(plan2.id());
        assertEquals(PlanStatus.CONFIRMED.name(), planService.get(plan2.id()).status());
    }

    @Test
    void 潮水窗口内部重叠冲突() {
        confirmedPlan("PLAN-OVER-1", 1L, List.of(1L), BASE.plusHours(10), BASE.plusHours(12));
        PlanResponse plan2 = planService.apply("PLAN-OVER-2", "合成货轮", "V002");
        BusinessException e = assertThrows(BusinessException.class, () -> planService.arrange(
                plan2.id(), 2L, List.of(1L), BASE.plusHours(11), BASE.plusHours(13)));
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, e.status());
        assertEquals("TUG_OCCUPIED", e.code());
    }

    @Test
    void 引航员连续作业超4小时编排被拒绝() {
        confirmedPlan("PLAN-WORK-1", 1L, List.of(1L), BASE.plusHours(8), BASE.plusHours(12));
        PlanResponse plan2 = planService.apply("PLAN-WORK-2", "合成货轮", "V002");
        // 仅休息 30 分钟再作业 4 小时 -> 连续段累计 8 小时，违规
        BusinessException e = assertThrows(BusinessException.class, () -> planService.arrange(
                plan2.id(), 1L, List.of(2L), BASE.plusHours(12).plusMinutes(30), BASE.plusHours(16).plusMinutes(30)));
        assertEquals("WORK_HOUR_VIOLATION", e.code());
    }

    @Test
    void 确认失败整体回滚不留部分锁定() {
        PlanResponse planA = planService.apply("PLAN-RB-A", "合成货轮A", "V001");
        planService.arrange(planA.id(), 1L, List.of(1L, 2L), BASE.plusHours(10), BASE.plusHours(12));
        PlanResponse planB = planService.apply("PLAN-RB-B", "合成货轮B", "V002");
        planService.arrange(planB.id(), 2L, List.of(2L), BASE.plusHours(11), BASE.plusHours(13));
        planService.confirm(planB.id());

        BusinessException e = assertThrows(BusinessException.class, () -> planService.confirm(planA.id()));
        assertEquals("TUG_OCCUPIED", e.code());
        assertEquals(0, lockRepository.countActiveLocksByPlan(planA.id()), "回滚后不得残留任何锁定");
        assertEquals(PlanStatus.ARRANGED.name(), planService.get(planA.id()).status(), "状态应保持编排态");
    }

    @Test
    void 恰满2小时前取消不收违约记录() {
        PlanResponse plan = confirmedPlan("PLAN-CXL-OK", 1L, List.of(1L),
                BASE.plusHours(5), BASE.plusHours(7));
        clock.set(BASE.plusHours(3)); // 恰为执行前 2 小时
        PlanResponse cancelled = planService.cancel(plan.id(), "船方取消");
        assertEquals(PlanStatus.CANCELLED.name(), cancelled.status());
        assertEquals(0, breachRepository.countByPlan(plan.id()), "恰满 2 小时仍可免费取消");
        assertEquals(0, lockRepository.countActiveLocksByPlan(plan.id()), "取消后锁定释放");
    }

    @Test
    void 不足2小时取消收取合成违约记录() {
        PlanResponse plan = confirmedPlan("PLAN-CXL-LATE", 1L, List.of(1L),
                BASE.plusHours(5), BASE.plusHours(7));
        clock.set(BASE.plusHours(3).plusMinutes(1)); // 执行前 1 小时 59 分
        PlanResponse cancelled = planService.cancel(plan.id(), "迟到取消");
        assertEquals(PlanStatus.CANCELLED.name(), cancelled.status());
        assertEquals(1, breachRepository.countByPlan(plan.id()), "不足 2 小时须收取合成违约记录");
        assertTrue(breachRepository.findReasonsByPlan(plan.id()).get(0).contains("不足 2 小时"));
    }

    @Test
    void 非法状态流转被拒绝() {
        PlanResponse plan = planService.apply("PLAN-ST-1", "合成货轮", "V001");
        BusinessException e1 = assertThrows(BusinessException.class, () -> planService.start(plan.id()));
        assertEquals("INVALID_STATE", e1.code());
        BusinessException e2 = assertThrows(BusinessException.class, () -> planService.settle(plan.id()));
        assertEquals("INVALID_STATE", e2.code());

        PlanResponse confirmed = confirmedPlan("PLAN-ST-2", 2L, List.of(3L),
                BASE.plusHours(8), BASE.plusHours(9));
        planService.start(confirmed.id());
        BusinessException e3 = assertThrows(BusinessException.class,
                () -> planService.cancel(confirmed.id(), "执行中取消"));
        assertEquals("INVALID_STATE", e3.code(), "执行中计划不允许取消");
    }

    @Test
    void 同一引航员同时刻仅允许一条执行中计划() {
        PlanResponse plan1 = confirmedPlan("PLAN-EX-1", 1L, List.of(1L),
                BASE.plusHours(8), BASE.plusHours(9));
        PlanResponse plan2 = confirmedPlan("PLAN-EX-2", 1L, List.of(2L),
                BASE.plusHours(10), BASE.plusHours(11));
        planService.start(plan1.id());
        BusinessException e = assertThrows(BusinessException.class, () -> planService.start(plan2.id()));
        assertEquals("PILOT_EXECUTING_CONFLICT", e.code());
        assertEquals(1, lockRepository.countExecutingLocks());

        planService.settle(plan1.id());
        planService.start(plan2.id());
        assertEquals(PlanStatus.EXECUTING.name(), planService.get(plan2.id()).status());
    }
}
