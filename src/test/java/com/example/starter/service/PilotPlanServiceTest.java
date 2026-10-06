package com.example.starter.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import com.example.starter.TestClockConfig;
import com.example.starter.web.ApiException;
import com.example.starter.web.dto.PlanResponse;
import com.example.starter.web.dto.Requests.ApplyRequest;
import com.example.starter.web.dto.Requests.CancelRequest;
import com.example.starter.web.dto.Requests.ConfirmRequest;
import com.example.starter.web.dto.Requests.RescheduleRequest;
import com.example.starter.web.dto.Requests.ScheduleRequest;
import com.example.starter.web.dto.Requests.SettleRequest;
import com.example.starter.web.dto.Requests.StartRequest;

/**
 * 引航计划服务 H2 集成测试：状态流转主流程、工时恰边界、半开区间冲突、
 * 锁定原子性、迟到取消边界、幂等语义与改期重新校验。
 * 使用独立命名内存库 pilot_test，每用例前清空业务表。
 */
@SpringBootTest
@Import(TestClockConfig.class)
class PilotPlanServiceTest {

    private static final LocalDateTime NOW = TestClockConfig.BASE_TIME;
    /** 计划基准日：时钟当前日的次日，保证取消提前量可控。 */
    private static final LocalDateTime DAY = NOW.toLocalDate().plusDays(1).atTime(0, 0);

    @Autowired
    private PilotPlanService service;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM idempotency_record");
        jdbc.update("DELETE FROM violation_record");
        jdbc.update("DELETE FROM resource_lock");
        jdbc.update("DELETE FROM plan_tug");
        jdbc.update("DELETE FROM pilot_plan");
        TestClockConfig.mutableClock().setNow(NOW);
    }

    private long apply(String req, String ship, LocalDateTime start, LocalDateTime end) {
        return service.apply(new ApplyRequest(req, "测试船代", ship, start, end)).planId();
    }

    private void schedule(String req, long planId, long pilotId, List<Long> tugIds,
                          LocalDateTime tideStart, LocalDateTime tideEnd) {
        service.schedule(new ScheduleRequest(req, planId, pilotId, tugIds, tideStart, tideEnd));
    }

    private PlanResponse confirm(String req, long planId) {
        return service.confirm(new ConfirmRequest(req, planId));
    }

    private int lockCount(long planId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM resource_lock WHERE plan_id = ?", Integer.class, planId);
        return count == null ? 0 : count;
    }

    private int violationCount(long planId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM violation_record WHERE plan_id = ?", Integer.class, planId);
        return count == null ? 0 : count;
    }

    private LocalDateTime tugLockStart(long planId) {
        return jdbc.queryForObject(
                        "SELECT lock_start FROM resource_lock WHERE plan_id = ? AND resource_type = 'TUG'",
                        java.sql.Timestamp.class, planId)
                .toLocalDateTime();
    }

    @Test
    @DisplayName("主流程：申请→编排→确认→执行→核销，确认后引航员与拖轮同时锁定，核销后释放")
    void fullLifecycle() {
        long planId = apply("L-apply", "远洋轮", DAY.plusHours(8), DAY.plusHours(11));
        schedule("L-sched", planId, 1L, List.of(1L, 2L), DAY.plusHours(7), DAY.plusHours(12));
        PlanResponse confirmed = confirm("L-confirm", planId);
        assertThat(confirmed.status()).isEqualTo("CONFIRMED");
        // 锁定与确认同事务：1 条引航员锁 + 2 条拖轮锁
        assertThat(lockCount(planId)).isEqualTo(3);

        PlanResponse started = service.start(new StartRequest("L-start", planId));
        assertThat(started.status()).isEqualTo("EXECUTING");

        PlanResponse settled = service.settle(new SettleRequest("L-settle", planId));
        assertThat(settled.status()).isEqualTo("SETTLED");
        assertThat(lockCount(planId)).isZero();
    }

    @Test
    @DisplayName("半开区间：拖轮占用端点相接不冲突，内部重叠冲突")
    void tugOccupationHalfOpen() {
        // 计划A：08:00-12:00 确认，占用拖轮1
        long planA = apply("H-applyA", "甲轮", DAY.plusHours(8), DAY.plusHours(12));
        schedule("H-schedA", planA, 1L, List.of(1L), DAY.plusHours(8), DAY.plusHours(12));
        confirm("H-confirmA", planA);

        // 计划B：12:00-14:00 与 A 端点相接（恰在 A 结束时开始），同一拖轮可确认
        long planB = apply("H-applyB", "乙轮", DAY.plusHours(12), DAY.plusHours(14));
        schedule("H-schedB", planB, 2L, List.of(1L), DAY.plusHours(12), DAY.plusHours(14));
        assertThat(confirm("H-confirmB", planB).status()).isEqualTo("CONFIRMED");

        // 计划C：13:00-15:00 与 B 内部重叠，同一拖轮确认冲突
        long planC = apply("H-applyC", "丙轮", DAY.plusHours(13), DAY.plusHours(15));
        schedule("H-schedC", planC, 3L, List.of(1L), DAY.plusHours(13), DAY.plusHours(15));
        assertThatThrownBy(() -> confirm("H-confirmC", planC))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("拖轮");
    }

    @Test
    @DisplayName("锁定原子性：拖轮组合中一条被占用则确认整体失败，不产生任何锁")
    void confirmLockAtomicity() {
        long planA = apply("A-applyA", "甲轮", DAY.plusHours(8), DAY.plusHours(12));
        schedule("A-schedA", planA, 1L, List.of(1L), DAY.plusHours(8), DAY.plusHours(12));
        confirm("A-confirmA", planA);

        // 计划B 绑定拖轮 1、2，拖轮 1 已被 A 锁定
        long planB = apply("A-applyB", "乙轮", DAY.plusHours(9), DAY.plusHours(11));
        schedule("A-schedB", planB, 2L, List.of(1L, 2L), DAY.plusHours(9), DAY.plusHours(11));
        assertThatThrownBy(() -> confirm("A-confirmB", planB))
                .isInstanceOf(ApiException.class);
        // 原子性：B 的引航员锁与拖轮 2 的锁都不应残留
        assertThat(lockCount(planB)).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT status FROM pilot_plan WHERE id = ?", String.class, planB))
                .isEqualTo("SCHEDULED");
    }

    @Test
    @DisplayName("工时恰边界集成：连续作业恰满 4 小时后休息不足 1 小时不可编排，恰满 1 小时可编排")
    void workHourBoundaryIntegration() {
        long planA = apply("W-applyA", "甲轮", DAY.plusHours(8), DAY.plusHours(12));
        schedule("W-schedA", planA, 1L, List.of(1L), DAY.plusHours(8), DAY.plusHours(12));

        // 休息 59 分钟：12:59 开始，违规
        long planB = apply("W-applyB", "乙轮", DAY.plusHours(12).plusMinutes(59), DAY.plusHours(14));
        assertThatThrownBy(() -> schedule("W-schedB", planB, 1L, List.of(2L),
                DAY.plusHours(12), DAY.plusHours(15)))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("4 小时");

        // 恰休息 1 小时：13:00 开始，合法
        long planC = apply("W-applyC", "丙轮", DAY.plusHours(13), DAY.plusHours(15));
        schedule("W-schedC", planC, 1L, List.of(2L), DAY.plusHours(13), DAY.plusHours(15));
        assertThat(jdbc.queryForObject(
                "SELECT status FROM pilot_plan WHERE id = ?", String.class, planC))
                .isEqualTo("SCHEDULED");
    }

    @Test
    @DisplayName("迟到取消：恰满 2 小时可免费取消，不足 2 小时合成违约记录，到达执行时刻不可取消")
    void cancelBoundaries() {
        // 恰满 2 小时：当前 08:00，计划次日 10:00 执行，时钟推进到次日 08:00
        long planA = apply("C-applyA", "甲轮", DAY.plusHours(10), DAY.plusHours(12));
        schedule("C-schedA", planA, 1L, List.of(1L), DAY.plusHours(9), DAY.plusHours(13));
        confirm("C-confirmA", planA);
        TestClockConfig.mutableClock().setNow(DAY.plusHours(8));
        PlanResponse cancelled = service.cancel(new CancelRequest("C-cancelA", planA));
        assertThat(cancelled.status()).isEqualTo("CANCELLED");
        assertThat(cancelled.violationCreated()).isFalse();
        assertThat(violationCount(planA)).isZero();
        assertThat(lockCount(planA)).isZero();

        // 不足 2 小时（提前 90 分钟）：取消成功但合成违约记录
        long planB = apply("C-applyB", "乙轮", DAY.plusHours(10), DAY.plusHours(12));
        schedule("C-schedB", planB, 2L, List.of(2L), DAY.plusHours(9), DAY.plusHours(13));
        TestClockConfig.mutableClock().setNow(DAY.plusHours(8).plusMinutes(30));
        PlanResponse lateCancelled = service.cancel(new CancelRequest("C-cancelB", planB));
        assertThat(lateCancelled.status()).isEqualTo("CANCELLED");
        assertThat(lateCancelled.violationCreated()).isTrue();
        assertThat(violationCount(planB)).isEqualTo(1);

        // 已到达执行时刻：不可取消
        long planC = apply("C-applyC", "丙轮", DAY.plusHours(10), DAY.plusHours(12));
        schedule("C-schedC", planC, 3L, List.of(3L), DAY.plusHours(9), DAY.plusHours(13));
        TestClockConfig.mutableClock().setNow(DAY.plusHours(10));
        assertThatThrownBy(() -> service.cancel(new CancelRequest("C-cancelC", planC)))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("不可取消");
    }

    @Test
    @DisplayName("幂等：同键同参返回首次结果且不重复落库，同键异参返回 409")
    void idempotencySemantics() {
        PlanResponse first = service.apply(new ApplyRequest(
                "I-same", "测试船代", "远洋轮", DAY.plusHours(8), DAY.plusHours(10)));
        PlanResponse replay = service.apply(new ApplyRequest(
                "I-same", "测试船代", "远洋轮", DAY.plusHours(8), DAY.plusHours(10)));
        assertThat(replay.planId()).isEqualTo(first.planId());
        Integer planCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM pilot_plan WHERE request_id = 'I-same'", Integer.class);
        assertThat(planCount).isEqualTo(1);

        assertThatThrownBy(() -> service.apply(new ApplyRequest(
                "I-same", "测试船代", "另一艘船", DAY.plusHours(8), DAY.plusHours(10))))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).status().value()).isEqualTo(409));
    }

    @Test
    @DisplayName("同一引航员同时刻只允许一条执行中计划")
    void singleExecutingPlanPerPilot() {
        long planA = apply("E-applyA", "甲轮", DAY.plusHours(8), DAY.plusHours(12));
        schedule("E-schedA", planA, 1L, List.of(1L), DAY.plusHours(8), DAY.plusHours(12));
        confirm("E-confirmA", planA);
        service.start(new StartRequest("E-startA", planA));

        // 直接构造另一时段重叠的已确认计划（绕过编排重叠校验，验证执行层防线）
        jdbc.update("INSERT INTO pilot_plan (request_id, applicant, ship_name, pilot_id,"
                        + " tide_start, tide_end, plan_start, plan_end, status, created_at, updated_at)"
                        + " VALUES ('E-raw', '测试船代', '乙轮', 1, ?, ?, ?, ?, 'CONFIRMED', ?, ?)",
                DAY.plusHours(10), DAY.plusHours(14), DAY.plusHours(10), DAY.plusHours(14),
                DAY, DAY);
        long rawPlanId = jdbc.queryForObject(
                "SELECT id FROM pilot_plan WHERE request_id = 'E-raw'", Long.class);
        assertThatThrownBy(() -> service.start(new StartRequest("E-startB", rawPlanId)))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("执行中");
    }

    @Test
    @DisplayName("改期：重新校验拖轮占用，结论与重新锁定同事务；冲突时旧锁保持不变")
    void rescheduleRevalidatesAndRelocksAtomically() {
        // 计划A：拖轮1，08:00-12:00，已确认
        long planA = apply("R-applyA", "甲轮", DAY.plusHours(8), DAY.plusHours(12));
        schedule("R-schedA", planA, 1L, List.of(1L), DAY.plusHours(8), DAY.plusHours(12));
        confirm("R-confirmA", planA);

        // 计划B：拖轮1，14:00-16:00，已确认
        long planB = apply("R-applyB", "乙轮", DAY.plusHours(14), DAY.plusHours(16));
        schedule("R-schedB", planB, 2L, List.of(1L), DAY.plusHours(14), DAY.plusHours(16));
        confirm("R-confirmB", planB);

        // B 改期到 10:00-12:00 与 A 的拖轮锁内部重叠 → 409，且 B 的旧锁保持 14:00-16:00
        assertThatThrownBy(() -> service.reschedule(new RescheduleRequest(
                "R-reschedB", planB, DAY.plusHours(10), DAY.plusHours(12),
                DAY.plusHours(10), DAY.plusHours(12))))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("拖轮");
        assertThat(tugLockStart(planB)).isEqualTo(DAY.plusHours(14));

        // B 改期到 16:00-18:00（与 A 端点相接之外的空档）→ 成功，锁同步更新
        PlanResponse rescheduled = service.reschedule(new RescheduleRequest(
                "R-reschedB2", planB, DAY.plusHours(16), DAY.plusHours(18),
                DAY.plusHours(16), DAY.plusHours(18)));
        assertThat(rescheduled.status()).isEqualTo("CONFIRMED");
        assertThat(tugLockStart(planB)).isEqualTo(DAY.plusHours(16));
    }

    @Test
    @DisplayName("改期：重新校验引航员工时，违反强制休息则拒绝")
    void rescheduleRevalidatesWorkHours() {
        // 引航员1：08:00-12:00 恰 4 小时连续作业
        long planA = apply("RW-applyA", "甲轮", DAY.plusHours(8), DAY.plusHours(12));
        schedule("RW-schedA", planA, 1L, List.of(1L), DAY.plusHours(8), DAY.plusHours(12));

        // 引航员1 另一条计划 13:00-15:00（恰休息 1 小时，合法编排）
        long planB = apply("RW-applyB", "乙轮", DAY.plusHours(13), DAY.plusHours(15));
        schedule("RW-schedB", planB, 1L, List.of(2L), DAY.plusHours(13), DAY.plusHours(15));

        // 改期到 12:30-14:30：距上一条仅 30 分钟，违反强制休息 → 409
        assertThatThrownBy(() -> service.reschedule(new RescheduleRequest(
                "RW-reschedB", planB, DAY.plusHours(12).plusMinutes(30), DAY.plusHours(14).plusMinutes(30),
                DAY.plusHours(12), DAY.plusHours(15))))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("4 小时");
    }

    @Test
    @DisplayName("状态机约束：未编排不可确认，执行中不可取消")
    void stateMachineGuards() {
        long planId = apply("S-apply", "甲轮", DAY.plusHours(8), DAY.plusHours(10));
        assertThatThrownBy(() -> confirm("S-confirm", planId))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("已编排");

        schedule("S-sched", planId, 1L, List.of(1L), DAY.plusHours(8), DAY.plusHours(10));
        confirm("S-confirm2", planId);
        service.start(new StartRequest("S-start", planId));
        assertThatThrownBy(() -> service.cancel(new CancelRequest("S-cancel", planId)))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("不可取消");
    }
}
