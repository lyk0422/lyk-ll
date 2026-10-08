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
import com.example.starter.web.dto.HandoverResponse;
import com.example.starter.web.dto.Requests.ApplyRequest;
import com.example.starter.web.dto.Requests.ConfirmRequest;
import com.example.starter.web.dto.Requests.HandoverApplyRequest;
import com.example.starter.web.dto.Requests.HandoverCompleteRequest;
import com.example.starter.web.dto.Requests.HandoverConfirmRequest;
import com.example.starter.web.dto.Requests.HandoverWithdrawRequest;
import com.example.starter.web.dto.Requests.ScheduleRequest;
import com.example.starter.web.dto.Requests.SettleRequest;
import com.example.starter.web.dto.Requests.StartRequest;

/**
 * 执行中引航交班 H2 集成测试：端点拒绝、双方签认、工时分段、撤回、
 * 核销竞争关闭、版本乐观锁与 requestId 幂等。
 * 使用独立命名内存库 pilot_test，每用例前清空业务表（含交班表）。
 */
@SpringBootTest
@Import(TestClockConfig.class)
class HandoverServiceTest {

    private static final LocalDateTime NOW = TestClockConfig.BASE_TIME;
    /** 计划基准日：时钟当前日的次日 00:00；合成引航 00:00-04:00。 */
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

    /** 创建一条 00:00-04:00、P1、拖轮1+2、宽潮窗（前一日23:00～当日05:00）的执行中计划。 */
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

    private long executingPlan00To04(String tag) {
        return executingPlan(tag, 1L, List.of(1L, 2L), DAY.plusHours(0), DAY.plusHours(4));
    }

    private Integer pilotLockCount(long planId, long pilotId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM resource_lock"
                + " WHERE plan_id = ? AND resource_type = 'PILOT' AND resource_id = ?",
                Integer.class, planId, pilotId);
    }

    private LocalDateTime pilotLockEnd(long planId, long pilotId) {
        return jdbc.queryForObject("SELECT lock_end FROM resource_lock"
                        + " WHERE plan_id = ? AND resource_type = 'PILOT' AND resource_id = ?",
                        java.sql.Timestamp.class, planId, pilotId)
                .toLocalDateTime();
    }

    @Test
    @DisplayName("主流程：02:00 交班 P1→P2，申请后原人保留并建立接班预占，双方签认到点原子切换，各记2小时")
    void applyConfirmSwitchSplitsResponsibility() {
        long planId = executingPlan00To04("M");

        HandoverResponse applied = handoverService.apply(new HandoverApplyRequest(
                "M-ho-apply", planId, 1L, 2L, DAY.plusHours(2)));
        assertThat(applied.status()).isEqualTo("PENDING");
        assertThat(applied.version()).isEqualTo(1L);
        // 原拖轮组合不变：仍 2 条 TUG 锁
        Integer tugLocks = jdbc.queryForObject(
                "SELECT COUNT(*) FROM resource_lock WHERE plan_id = ? AND resource_type = 'TUG'",
                Integer.class, planId);
        assertThat(tugLocks).isEqualTo(2);
        // 原引航员 P1 整段占用保留，P2 建立 [02:00,04:00) 接班预占
        assertThat(pilotLockCount(planId, 1L)).isEqualTo(1);
        assertThat(pilotLockEnd(planId, 1L)).isEqualTo(DAY.plusHours(4));
        assertThat(pilotLockCount(planId, 2L)).isEqualTo(1);
        assertThat(pilotLockEnd(planId, 2L)).isEqualTo(DAY.plusHours(4));
        assertThat(applied.successorPreoccupied()).isTrue();
        assertThat(applied.successorPreemptStart()).isEqualTo(DAY.plusHours(2));

        // 双方分别签认，身份对应本人；版本每次成功 +1
        HandoverResponse fromSigned = handoverService.confirm(new HandoverConfirmRequest(
                "M-ho-from", applied.handoverId(), 1L, "P1 交清", 1L));
        assertThat(fromSigned.fromConfirmed()).isTrue();
        assertThat(fromSigned.toConfirmed()).isFalse();
        assertThat(fromSigned.version()).isEqualTo(2L);
        HandoverResponse bothSigned = handoverService.confirm(new HandoverConfirmRequest(
                "M-ho-to", applied.handoverId(), 2L, "P2 接稳", 2L));
        assertThat(bothSigned.toConfirmed()).isTrue();
        assertThat(bothSigned.version()).isEqualTo(3L);

        // 未到约定交班时刻不可切换
        assertThatThrownBy(() -> handoverService.complete(new HandoverCompleteRequest(
                "M-ho-complete-early", applied.handoverId(), 3L)))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("交班时刻");

        // 时钟到达 02:00，原子切换
        TestClockConfig.mutableClock().setNow(DAY.plusHours(2));
        HandoverResponse switched = handoverService.complete(new HandoverCompleteRequest(
                "M-ho-complete", applied.handoverId(), 3L));
        assertThat(switched.status()).isEqualTo("SWITCHED");
        assertThat(switched.version()).isEqualTo(4L);
        // 责任区间在 02:00 分割
        assertThat(switched.segments()).containsExactly(
                new HandoverResponse.Segment(1L, DAY.plusHours(0), DAY.plusHours(2)),
                new HandoverResponse.Segment(2L, DAY.plusHours(2), DAY.plusHours(4)));

        // 计划改挂 P2，版本推进
        Long currentPilot = jdbc.queryForObject(
                "SELECT pilot_id FROM pilot_plan WHERE id = ?", Long.class, planId);
        Long planVersion = jdbc.queryForObject(
                "SELECT version FROM pilot_plan WHERE id = ?", Long.class, planId);
        assertThat(currentPilot).isEqualTo(2L);
        assertThat(planVersion).isEqualTo(2L);
        // P1 锁收窄到 02:00，P2 持剩余段；拖轮仍只锁原计划
        assertThat(pilotLockEnd(planId, 1L)).isEqualTo(DAY.plusHours(2));
        assertThat(pilotLockEnd(planId, 2L)).isEqualTo(DAY.plusHours(4));
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM resource_lock WHERE plan_id = ? AND resource_type = 'TUG'",
                Integer.class, planId)).isEqualTo(2);

        // 两人各记 2 小时，合计 4 小时（不重复计整计划）
        HandoverService.WorkHourView p1 = handoverService.workHours(1L, DAY.toLocalDate());
        HandoverService.WorkHourView p2 = handoverService.workHours(2L, DAY.toLocalDate());
        assertThat(p1.totalMinutes()).isEqualTo(120);
        assertThat(p2.totalMinutes()).isEqualTo(120);
        assertThat(p1.totalMinutes() + p2.totalMinutes()).isEqualTo(240);
    }

    @Test
    @DisplayName("端点拒绝：交班时刻等于计划/潮窗起点或终点一律 400，且不产生交班单与接班预占")
    void handoverAtEndpointsRejected() {
        long planId = executingPlan00To04("EP");

        List<LocalDateTime> endpoints = List.of(
                DAY.plusHours(0),           // 计划起点（亦在宽潮窗内部）
                DAY.plusHours(4),           // 计划终点
                DAY.minusHours(1),          // 潮窗起点（在计划外）
                DAY.plusHours(5));          // 潮窗终点（在计划外）
        int i = 0;
        for (LocalDateTime at : endpoints) {
            String req = "EP-ho-" + i++;
            assertThatThrownBy(() -> handoverService.apply(new HandoverApplyRequest(
                    req, planId, 1L, 2L, at)))
                    .isInstanceOf(ApiException.class)
                    .satisfies(ex -> assertThat(((ApiException) ex).status().value()).isEqualTo(400));
            // 失败不占键：同 requestId 随后可用于合法申请
            // 且未残留任何交班单 / P2 预占
        }
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM pilot_handover WHERE plan_id = ?", Integer.class, planId))
                .isZero();
        assertThat(pilotLockCount(planId, 2L)).isZero();
    }

    @Test
    @DisplayName("接班员被其他计划占用：整单拒绝，不先释放原引航员，不残留接班预占")
    void successorBusyRejectsWholeRequestAndKeepsOriginalPilot() {
        long planId = executingPlan00To04("B");
        // P2 另有一条 03:00-05:00 已确认计划（产生 PILOT 锁），与剩余段 [02:00,04:00) 内部重叠
        executingPlan("B-other", 2L, List.of(3L), DAY.plusHours(3), DAY.plusHours(5));

        assertThatThrownBy(() -> handoverService.apply(new HandoverApplyRequest(
                "B-ho", planId, 1L, 2L, DAY.plusHours(2))))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("占用");

        // P1 整段锁保留，P2 无该计划预占，无交班单
        assertThat(pilotLockEnd(planId, 1L)).isEqualTo(DAY.plusHours(4));
        assertThat(pilotLockCount(planId, 2L)).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM pilot_handover WHERE plan_id = ?", Integer.class, planId))
                .isZero();
    }

    @Test
    @DisplayName("接班员工时门槛：接班使单日累计超过10小时则拒绝")
    void successorDailyHourLimitRejected() {
        long planId = executingPlan00To04("WH");
        // P2 已有 04:00-14:00 恰 10 小时（端点相接，不占用冲突）；接班段 02:00-04:00 使累计达 12 小时
        executingPlan("WH-other", 2L, List.of(3L), DAY.plusHours(4), DAY.plusHours(14));

        assertThatThrownBy(() -> handoverService.apply(new HandoverApplyRequest(
                "WH-ho", planId, 1L, 2L, DAY.plusHours(2))))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("10 小时");
        assertThat(pilotLockCount(planId, 2L)).isZero();
    }

    @Test
    @DisplayName("接班员连续作业满4小时后休息不足1小时：交班申请拒绝")
    void successorRestRuleRejected() {
        long planId = executingPlan00To04("WR");
        // P2 已有 04:00-06:00 与 06:30-08:30 两段（间隔30分钟串联）。接班段 02:00-04:00 与首段相接，
        // 使 06:30 计划开始前的连续作业块累计达 4 小时（02-04 + 04-06），触发强制休息。
        executingPlan("WR-other1", 2L, List.of(3L), DAY.plusHours(4), DAY.plusHours(6));
        executingPlan("WR-other2", 2L, List.of(4L), DAY.plusHours(6).plusMinutes(30),
                DAY.plusHours(8).plusMinutes(30));

        assertThatThrownBy(() -> handoverService.apply(new HandoverApplyRequest(
                "WR-ho", planId, 1L, 2L, DAY.plusHours(2))))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("4 小时");
    }

    @Test
    @DisplayName("同一计划只允许一条未结束交班；出班员必须是当前责任人，接班员必须不同")
    void singlePendingAndIdentityGuards() {
        long planId = executingPlan00To04("S");
        handoverService.apply(new HandoverApplyRequest("S-ho", planId, 1L, 2L, DAY.plusHours(2)));

        assertThatThrownBy(() -> handoverService.apply(new HandoverApplyRequest(
                "S-ho2", planId, 1L, 3L, DAY.plusHours(2))))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("未结束");
        assertThatThrownBy(() -> handoverService.apply(new HandoverApplyRequest(
                "S-ho3", planId, 2L, 3L, DAY.plusHours(2))))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("当前责任人");
        assertThatThrownBy(() -> handoverService.apply(new HandoverApplyRequest(
                "S-ho4", planId, 1L, 1L, DAY.plusHours(2))))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).status().value()).isEqualTo(400));
    }

    @Test
    @DisplayName("签认身份必须对应双方，错误身份与重复签认均409；未双方签认不可切换")
    void confirmIdentityAndBothRequired() {
        long planId = executingPlan00To04("C");
        HandoverResponse applied = handoverService.apply(new HandoverApplyRequest(
                "C-ho", planId, 1L, 2L, DAY.plusHours(2)));

        assertThatThrownBy(() -> handoverService.confirm(new HandoverConfirmRequest(
                "C-who", applied.handoverId(), 3L, "外人", 1L)))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("双方");
        handoverService.confirm(new HandoverConfirmRequest(
                "C-from", applied.handoverId(), 1L, "P1", 1L));
        // P1 重复签认
        assertThatThrownBy(() -> handoverService.confirm(new HandoverConfirmRequest(
                "C-from-again", applied.handoverId(), 1L, "P1", 2L)))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("已签认");
        // 仅一方签认，到点也不可切换
        TestClockConfig.mutableClock().setNow(DAY.plusHours(2));
        assertThatThrownBy(() -> handoverService.complete(new HandoverCompleteRequest(
                "C-complete", applied.handoverId(), 2L)))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("双方");
    }

    @Test
    @DisplayName("版本：expectedVersion 过期返回409，成功后版本加1；切换后不可再切换或撤回")
    void optimisticVersionAndTerminalState() {
        long planId = executingPlan00To04("V");
        HandoverResponse applied = handoverService.apply(new HandoverApplyRequest(
                "V-ho", planId, 1L, 2L, DAY.plusHours(2)));
        // 错误版本签认 → 409
        assertThatThrownBy(() -> handoverService.confirm(new HandoverConfirmRequest(
                "V-bad", applied.handoverId(), 1L, "x", 99L)))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).status().value()).isEqualTo(409));
        handoverService.confirm(new HandoverConfirmRequest(
                "V-from", applied.handoverId(), 1L, "P1", 1L));
        handoverService.confirm(new HandoverConfirmRequest(
                "V-to", applied.handoverId(), 2L, "P2", 2L));
        TestClockConfig.mutableClock().setNow(DAY.plusHours(2));
        // 错误版本完成 → 409
        assertThatThrownBy(() -> handoverService.complete(new HandoverCompleteRequest(
                "V-complete-bad", applied.handoverId(), 2L)))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).status().value()).isEqualTo(409));
        handoverService.complete(new HandoverCompleteRequest(
                "V-complete", applied.handoverId(), 3L));

        assertThatThrownBy(() -> handoverService.withdraw(new HandoverWithdrawRequest(
                "V-withdraw", applied.handoverId(), 1L, 4L)))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("已结束");
        assertThatThrownBy(() -> handoverService.complete(new HandoverCompleteRequest(
                "V-complete-again", applied.handoverId(), 4L)))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("已结束");
    }

    @Test
    @DisplayName("切换前撤回：释放接班预占、保留原引航员整段；撤回后可重新申请交班")
    void withdrawBeforeSwitchReleasesPreemption() {
        long planId = executingPlan00To04("W");
        HandoverResponse applied = handoverService.apply(new HandoverApplyRequest(
                "W-ho", planId, 1L, 2L, DAY.plusHours(2)));

        // 非出班员不可撤回
        assertThatThrownBy(() -> handoverService.withdraw(new HandoverWithdrawRequest(
                "W-who", applied.handoverId(), 2L, 1L)))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("出班");

        HandoverResponse withdrawn = handoverService.withdraw(new HandoverWithdrawRequest(
                "W-withdraw", applied.handoverId(), 1L, 1L));
        assertThat(withdrawn.status()).isEqualTo("WITHDRAWN");
        assertThat(withdrawn.successorPreoccupied()).isFalse();
        // P2 预占释放，P1 整段保留，拖轮不变
        assertThat(pilotLockCount(planId, 2L)).isZero();
        assertThat(pilotLockEnd(planId, 1L)).isEqualTo(DAY.plusHours(4));

        // 撤回后同计划可再次申请
        HandoverResponse reapplied = handoverService.apply(new HandoverApplyRequest(
                "W-ho2", planId, 1L, 3L, DAY.plusHours(2)));
        assertThat(reapplied.status()).isEqualTo("PENDING");
    }

    @Test
    @DisplayName("核销与切换竞争（串行）：核销关闭未结束交班并释放预占，之后不能再切入新引航员")
    void settleClosesPendingHandover() {
        long planId = executingPlan00To04("X");
        HandoverResponse applied = handoverService.apply(new HandoverApplyRequest(
                "X-ho", planId, 1L, 2L, DAY.plusHours(2)));
        handoverService.confirm(new HandoverConfirmRequest(
                "X-from", applied.handoverId(), 1L, "P1", 1L));
        handoverService.confirm(new HandoverConfirmRequest(
                "X-to", applied.handoverId(), 2L, "P2", 2L));
        TestClockConfig.mutableClock().setNow(DAY.plusHours(2));

        // 核销先于切换：关闭交班、释放全部锁
        planService.settle(new SettleRequest("X-settle", planId));
        String handoverStatus = jdbc.queryForObject(
                "SELECT status FROM pilot_handover WHERE id = ?", String.class, applied.handoverId());
        assertThat(handoverStatus).isEqualTo("CLOSED");
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM resource_lock WHERE plan_id = ?", Integer.class, planId))
                .isZero();

        // 已核销不可再切入新引航员
        assertThatThrownBy(() -> handoverService.complete(new HandoverCompleteRequest(
                "X-complete", applied.handoverId(), 3L)))
                .isInstanceOf(ApiException.class);
        assertThat(jdbc.queryForObject(
                "SELECT pilot_id FROM pilot_plan WHERE id = ?", Long.class, planId))
                .isEqualTo(1L);
    }

    @Test
    @DisplayName("切换时重查来源计划版本：计划版本被改动则切换409，不切入新引航员")
    void completeRechecksPlanVersion() {
        long planId = executingPlan00To04("PV");
        HandoverResponse applied = handoverService.apply(new HandoverApplyRequest(
                "PV-ho", planId, 1L, 2L, DAY.plusHours(2)));
        handoverService.confirm(new HandoverConfirmRequest(
                "PV-from", applied.handoverId(), 1L, "P1", 1L));
        handoverService.confirm(new HandoverConfirmRequest(
                "PV-to", applied.handoverId(), 2L, "P2", 2L));
        // 模拟来源计划在交班期间被旧入口改动导致版本前进
        jdbc.update("UPDATE pilot_plan SET version = version + 1 WHERE id = ?", planId);
        TestClockConfig.mutableClock().setNow(DAY.plusHours(2));

        assertThatThrownBy(() -> handoverService.complete(new HandoverCompleteRequest(
                "PV-complete", applied.handoverId(), 3L)))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("版本");
        assertThat(jdbc.queryForObject(
                "SELECT status FROM pilot_handover WHERE id = ?", String.class, applied.handoverId()))
                .isEqualTo("PENDING");
        assertThat(jdbc.queryForObject(
                "SELECT pilot_id FROM pilot_plan WHERE id = ?", Long.class, planId))
                .isEqualTo(1L);
    }

    @Test
    @DisplayName("幂等：同参重放原响应，异参409，失败不占键")
    void idempotencyReplayConflictAndNoKeyOnFailure() {
        long planId = executingPlan00To04("I");
        HandoverApplyRequest req = new HandoverApplyRequest(
                "I-ho", planId, 1L, 2L, DAY.plusHours(2));
        HandoverResponse first = handoverService.apply(req);
        HandoverResponse replay = handoverService.apply(req);
        assertThat(replay.handoverId()).isEqualTo(first.handoverId());
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM pilot_handover WHERE plan_id = ?", Integer.class, planId))
                .isEqualTo(1);

        // 同键异参 → 409
        assertThatThrownBy(() -> handoverService.apply(new HandoverApplyRequest(
                "I-ho", planId, 1L, 3L, DAY.plusHours(2))))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).status().value()).isEqualTo(409));

        // 失败不占键：先用该键发起一次端点失败请求，再用同键发起合法申请应成功。
        // 使用另一条 P3 的 10:00-12:00 执行中计划（独立时段、独立拖轮，避免与首计划占用冲突）。
        long plan2 = executingPlan("I2", 3L, List.of(3L, 4L), DAY.plusHours(10), DAY.plusHours(12));
        assertThatThrownBy(() -> handoverService.apply(new HandoverApplyRequest(
                "I-fail-then-ok", plan2, 3L, 2L, DAY.plusHours(12))))
                .isInstanceOf(ApiException.class);
        HandoverResponse afterFailure = handoverService.apply(new HandoverApplyRequest(
                "I-fail-then-ok", plan2, 3L, 2L, DAY.plusHours(11)));
        assertThat(afterFailure.status()).isEqualTo("PENDING");
    }

    @Test
    @DisplayName("工时分段不清掉各人其他计划：P1 另有06:00-08:00计划，交班后仍计入")
    void workHoursKeepOtherPlansPerPilot() {
        long planId = executingPlan00To04("K");
        // P1 另一条 06:00-08:00 计划（走到 SCHEDULED 即纳入责任段）
        long otherId = planService.apply(new ApplyRequest(
                "K-other-apply", "测试船代", "合成轮-K2", DAY.plusHours(6), DAY.plusHours(8)))
                .planId();
        planService.schedule(new ScheduleRequest("K-other-sched", otherId, 1L, List.of(3L),
                DAY.plusHours(5), DAY.plusHours(9)));

        HandoverResponse applied = handoverService.apply(new HandoverApplyRequest(
                "K-ho", planId, 1L, 2L, DAY.plusHours(2)));
        handoverService.confirm(new HandoverConfirmRequest(
                "K-from", applied.handoverId(), 1L, "P1", 1L));
        handoverService.confirm(new HandoverConfirmRequest(
                "K-to", applied.handoverId(), 2L, "P2", 2L));
        TestClockConfig.mutableClock().setNow(DAY.plusHours(2));
        handoverService.complete(new HandoverCompleteRequest("K-done", applied.handoverId(), 3L));

        HandoverService.WorkHourView p1 = handoverService.workHours(1L, DAY.toLocalDate());
        HandoverService.WorkHourView p2 = handoverService.workHours(2L, DAY.toLocalDate());
        // P1：交班段 00-02（2h）+ 其他计划 06-08（2h）= 4h，两条责任段
        assertThat(p1.totalMinutes()).isEqualTo(240);
        assertThat(p1.entries()).hasSize(2);
        assertThat(p1.entries()).extracting(HandoverService.WorkHourView.Entry::planId)
                .containsExactly(planId, otherId);
        // P2：仅剩余段 02-04（2h）
        assertThat(p2.totalMinutes()).isEqualTo(120);
        assertThat(p2.entries()).hasSize(1);
    }

    @Test
    @DisplayName("查询视图：展示责任区间、双方意见与接班预占；未交班计划工时沿用整段旧语义")
    void queryViewAndLegacyPlanSemantics() {
        long planId = executingPlan00To04("Q");
        HandoverResponse applied = handoverService.apply(new HandoverApplyRequest(
                "Q-ho", planId, 1L, 2L, DAY.plusHours(2)));
        handoverService.confirm(new HandoverConfirmRequest(
                "Q-from", applied.handoverId(), 1L, "交清", 1L));

        HandoverResponse view = handoverService.get(applied.handoverId());
        assertThat(view.fromComment()).isEqualTo("交清");
        assertThat(view.toComment()).isNull();
        assertThat(view.successorPreoccupied()).isTrue();
        assertThat(view.successorPreemptStart()).isEqualTo(DAY.plusHours(2));
        assertThat(view.successorPreemptEnd()).isEqualTo(DAY.plusHours(4));
        // 未切换前整段仍归出班员
        assertThat(view.segments()).containsExactly(
                new HandoverResponse.Segment(1L, DAY.plusHours(0), DAY.plusHours(4)));

        // 未使用交班的计划：P3 的 10:00-12:00 计划沿用整段
        long legacyId = executingPlan("Q-legacy", 3L, List.of(4L),
                DAY.plusHours(10), DAY.plusHours(12));
        HandoverService.WorkHourView p3 = handoverService.workHours(3L, DAY.toLocalDate());
        assertThat(p3.totalMinutes()).isEqualTo(120);
        assertThat(p3.entries()).hasSize(1);
        assertThat(p3.entries().get(0).planId()).isEqualTo(legacyId);
    }
}
