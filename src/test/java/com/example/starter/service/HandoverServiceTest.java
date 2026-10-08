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
import com.example.starter.web.dto.PlanDetailResponse;
import com.example.starter.web.dto.Requests.ApplyRequest;
import com.example.starter.web.dto.Requests.ConfirmRequest;
import com.example.starter.web.dto.Requests.HandoverAckRequest;
import com.example.starter.web.dto.Requests.HandoverApplyRequest;
import com.example.starter.web.dto.Requests.HandoverWithdrawRequest;
import com.example.starter.web.dto.Requests.RescheduleRequest;
import com.example.starter.web.dto.Requests.ScheduleRequest;
import com.example.starter.web.dto.Requests.SettleRequest;
import com.example.starter.web.dto.Requests.StartRequest;

/**
 * 执行中引航交班 H2 集成测试：端点拒绝、双方签认与原子切换、工时分段、
 * 接班占用拒绝、单一未结束交班、撤回、核销关闭、版本与幂等语义、
 * 旧改期入口不绕过交班占用。使用独立命名内存库 pilot_test，每用例前清空业务表。
 */
@SpringBootTest
@Import(TestClockConfig.class)
class HandoverServiceTest {

    private static final LocalDateTime NOW = TestClockConfig.BASE_TIME;
    /** 计划基准日：时钟当前日的次日 00:00。 */
    private static final LocalDateTime DAY = NOW.toLocalDate().plusDays(1).atTime(0, 0);

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
        TestClockConfig.mutableClock().setNow(NOW);
    }

    /** 构造一条执行中计划：apply → schedule → confirm → start。 */
    private long executingPlan(String prefix, long pilotId, List<Long> tugIds,
                               LocalDateTime start, LocalDateTime end) {
        long planId = planService.apply(
                new ApplyRequest(prefix + "-apply", "测试船代", "合成轮", start, end)).planId();
        planService.schedule(new ScheduleRequest(prefix + "-sched", planId, pilotId, tugIds,
                start.minusHours(1), end.plusHours(1)));
        planService.confirm(new ConfirmRequest(prefix + "-confirm", planId));
        planService.start(new StartRequest(prefix + "-start", planId));
        return planId;
    }

    /** 查询指定引航员在计划上的锁区间；不存在返回 null。 */
    private LocalDateTime[] pilotLock(long planId, long pilotId) {
        List<LocalDateTime[]> rows = jdbc.query(
                "SELECT lock_start, lock_end FROM resource_lock"
                        + " WHERE plan_id = ? AND resource_type = 'PILOT' AND resource_id = ?",
                (rs, n) -> new LocalDateTime[]{
                        rs.getTimestamp(1).toLocalDateTime(), rs.getTimestamp(2).toLocalDateTime()},
                planId, pilotId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private int lockCount(long planId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM resource_lock WHERE plan_id = ?", Integer.class, planId);
        return count == null ? 0 : count;
    }

    private String handoverStatus(long handoverId) {
        return jdbc.queryForObject(
                "SELECT status FROM pilot_handover WHERE id = ?", String.class, handoverId);
    }

    private long currentPilot(long planId) {
        return jdbc.queryForObject("SELECT pilot_id FROM pilot_plan WHERE id = ?",
                Long.class, planId);
    }

    @Test
    @DisplayName("端点拒绝：交班时刻等于计划起点、终点或落在计划外均返回 400")
    void endpointMomentsRejected() {
        long planId = executingPlan("EP", 1L, List.of(1L), DAY, DAY.plusHours(4));

        assertThatThrownBy(() -> handoverService.apply(
                new HandoverApplyRequest("EP-ho1", planId, 1L, 2L, DAY)))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("端点")
                .satisfies(ex -> assertThat(((ApiException) ex).status().value()).isEqualTo(400));
        assertThatThrownBy(() -> handoverService.apply(
                new HandoverApplyRequest("EP-ho2", planId, 1L, 2L, DAY.plusHours(4))))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("端点");
        assertThatThrownBy(() -> handoverService.apply(
                new HandoverApplyRequest("EP-ho3", planId, 1L, 2L, DAY.plusHours(5))))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("端点");
        // 全部拒绝，不产生任何交班记录与预占
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM pilot_handover WHERE plan_id = ?", Integer.class, planId);
        assertThat(count).isZero();
        assertThat(lockCount(planId)).isEqualTo(2); // 仅原引航员锁 + 拖轮锁
    }

    @Test
    @DisplayName("主流程：双方签认且到达 02:00 原子切换，出班记 00:00-02:00、接班记 02:00-04:00")
    void dualAckAndAtomicSwitch() {
        // 00:00-04:00 合成引航执行中，P1 负责，拖轮 1
        long planId = executingPlan("M", 1L, List.of(1L), DAY, DAY.plusHours(4));

        // 计划 02:00 把责任从 P1 交给 P2
        HandoverResponse applied = handoverService.apply(
                new HandoverApplyRequest("M-ho", planId, 1L, 2L, DAY.plusHours(2)));
        assertThat(applied.status()).isEqualTo("OPEN");
        assertThat(applied.version()).isEqualTo(1);
        assertThat(applied.preOccupyStart()).isEqualTo(DAY.plusHours(2));
        assertThat(applied.preOccupyEnd()).isEqualTo(DAY.plusHours(4));
        long handoverId = applied.handoverId();
        // 旧引航员原占用保留，接班预占写入，拖轮锁不变
        assertThat(pilotLock(planId, 1L)).containsExactly(DAY, DAY.plusHours(4));
        assertThat(pilotLock(planId, 2L)).containsExactly(DAY.plusHours(2), DAY.plusHours(4));

        // 非交班双方签认 → 400
        assertThatThrownBy(() -> handoverService.ack(
                new HandoverAckRequest("M-ack-p3", handoverId, 3L, "越权", 1L)))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("双方")
                .satisfies(ex -> assertThat(((ApiException) ex).status().value()).isEqualTo(400));

        // 出班方签认（当前时刻 10-06 08:00，未到 02:00，不切换）
        HandoverResponse outAcked = handoverService.ack(
                new HandoverAckRequest("M-ack1", handoverId, 1L, "同意交班", 1L));
        assertThat(outAcked.outAck()).isTrue();
        assertThat(outAcked.outComment()).isEqualTo("同意交班");
        assertThat(outAcked.version()).isEqualTo(2);
        assertThat(outAcked.status()).isEqualTo("OPEN");

        // 接班方带过期版本签认 → 409
        assertThatThrownBy(() -> handoverService.ack(
                new HandoverAckRequest("M-ack-stale", handoverId, 2L, "同意接班", 1L)))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("版本")
                .satisfies(ex -> assertThat(((ApiException) ex).status().value()).isEqualTo(409));

        // 接班方签认：双方已确认但当前时刻未到 02:00，不切换
        HandoverResponse bothAcked = handoverService.ack(
                new HandoverAckRequest("M-ack2", handoverId, 2L, "同意接班", 2L));
        assertThat(bothAcked.inAck()).isTrue();
        assertThat(bothAcked.status()).isEqualTo("OPEN");
        assertThat(bothAcked.version()).isEqualTo(3);
        assertThat(currentPilot(planId)).isEqualTo(1L);

        // 当前时刻到达 02:00，再次触发后原子切换
        TestClockConfig.mutableClock().setNow(DAY.plusHours(2));
        HandoverResponse switched = handoverService.ack(
                new HandoverAckRequest("M-ack3", handoverId, 2L, null, 3L));
        assertThat(switched.status()).isEqualTo("SWITCHED");
        assertThat(switched.version()).isEqualTo(4);

        // 责任切换：计划当前引航员为 P2；P1 占用截断到 02:00，P2 承担 02:00-04:00
        assertThat(currentPilot(planId)).isEqualTo(2L);
        assertThat(pilotLock(planId, 1L)).containsExactly(DAY, DAY.plusHours(2));
        assertThat(pilotLock(planId, 2L)).containsExactly(DAY.plusHours(2), DAY.plusHours(4));
        // 拖轮仍只锁在原计划（1 条拖轮锁 + 2 条引航员锁）
        assertThat(lockCount(planId)).isEqualTo(3);
        LocalDateTime[] tugLock = jdbc.queryForObject(
                "SELECT lock_start, lock_end FROM resource_lock"
                        + " WHERE plan_id = ? AND resource_type = 'TUG'",
                (rs, n) -> new LocalDateTime[]{
                        rs.getTimestamp(1).toLocalDateTime(), rs.getTimestamp(2).toLocalDateTime()},
                planId);
        assertThat(tugLock).containsExactly(DAY, DAY.plusHours(4));

        // 查询展示责任区间与双方意见：两人各记两小时
        PlanDetailResponse detail = planService.getDetail(planId);
        assertThat(detail.responsibilities()).containsExactly(
                new PlanDetailResponse.ResponsibilitySegment(1L, DAY, DAY.plusHours(2)),
                new PlanDetailResponse.ResponsibilitySegment(2L, DAY.plusHours(2), DAY.plusHours(4)));
        assertThat(detail.handover().status()).isEqualTo("SWITCHED");
        assertThat(detail.handover().outComment()).isEqualTo("同意交班");
        assertThat(detail.handover().inComment()).isEqualTo("同意接班");
        assertThat(detail.handover().preOccupyStart()).isNull();

        // 已切换不能撤回
        assertThatThrownBy(() -> handoverService.withdraw(
                new HandoverWithdrawRequest("M-wd", handoverId, 4L)))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("不可撤回");
    }

    @Test
    @DisplayName("工时分段：切换后整计划不重复计给两人，各人其他计划工时不被清除")
    void workHourSegmentationAfterSwitch() {
        long planX = executingPlan("W", 1L, List.of(1L), DAY, DAY.plusHours(4));
        long handoverId = handoverService.apply(
                new HandoverApplyRequest("W-ho", planX, 1L, 2L, DAY.plusHours(2))).handoverId();
        handoverService.ack(new HandoverAckRequest("W-ack1", handoverId, 1L, "交", 1L));
        handoverService.ack(new HandoverAckRequest("W-ack2", handoverId, 2L, "接", 2L));
        TestClockConfig.mutableClock().setNow(DAY.plusHours(2));
        handoverService.ack(new HandoverAckRequest("W-ack3", handoverId, 2L, null, 3L));
        assertThat(handoverStatus(handoverId)).isEqualTo("SWITCHED");

        // P1 另有计划 08:00-10:00（不受交班影响）
        long planY = planService.apply(new ApplyRequest(
                "W-applyY", "测试船代", "乙轮", DAY.plusHours(8), DAY.plusHours(10))).planId();
        planService.schedule(new ScheduleRequest("W-schedY", planY, 1L, List.of(2L),
                DAY.plusHours(8), DAY.plusHours(10)));

        // P1 责任段已截断到 02:00：02:30-04:30 可编排（若整计划 4 小时仍记给 P1，
        // 则连续作业恰满 4 小时且休息不足 1 小时，必然违规）
        long planZ = planService.apply(new ApplyRequest(
                "W-applyZ", "测试船代", "丙轮", DAY.plusHours(2).plusMinutes(30),
                DAY.plusHours(4).plusMinutes(30))).planId();
        planService.schedule(new ScheduleRequest("W-schedZ", planZ, 1L, List.of(3L),
                DAY.plusHours(2), DAY.plusHours(5)));
        assertThat(jdbc.queryForObject("SELECT status FROM pilot_plan WHERE id = ?",
                String.class, planZ)).isEqualTo("SCHEDULED");

        // P2 承担 02:00-04:00：03:00-05:00 与其责任段重叠 → 409
        long planP2 = planService.apply(new ApplyRequest(
                "W-applyP2", "测试船代", "丁轮", DAY.plusHours(3), DAY.plusHours(5))).planId();
        assertThatThrownBy(() -> planService.schedule(new ScheduleRequest(
                "W-schedP2", planP2, 2L, List.of(4L), DAY.plusHours(3), DAY.plusHours(5))))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("已有计划");

        // P1 的其他计划工时不被清除：08:30-09:30 与计划 Y 重叠 → 409
        long planOverlap = planService.apply(new ApplyRequest(
                "W-applyO", "测试船代", "戊轮", DAY.plusHours(8).plusMinutes(30),
                DAY.plusHours(9).plusMinutes(30))).planId();
        assertThatThrownBy(() -> planService.schedule(new ScheduleRequest(
                "W-schedO", planOverlap, 1L, List.of(4L),
                DAY.plusHours(8), DAY.plusHours(10))))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("已有计划");
    }

    @Test
    @DisplayName("接班员被其他计划占用时整单拒绝，出班员原占用保留")
    void applyRejectedWhenSuccessorOccupied() {
        // P2 已确认 03:00-05:00 计划（锁定）
        long planB = planService.apply(new ApplyRequest(
                "O-applyB", "测试船代", "乙轮", DAY.plusHours(3), DAY.plusHours(5))).planId();
        planService.schedule(new ScheduleRequest("O-schedB", planB, 2L, List.of(2L),
                DAY.plusHours(3), DAY.plusHours(5)));
        planService.confirm(new ConfirmRequest("O-confirmB", planB));

        long planX = executingPlan("O", 1L, List.of(1L), DAY, DAY.plusHours(4));
        assertThatThrownBy(() -> handoverService.apply(
                new HandoverApplyRequest("O-ho", planX, 1L, 2L, DAY.plusHours(2))))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).status().value()).isEqualTo(409));

        // 整单拒绝：无交班记录、无接班预占，P1 原占用保留
        Integer handovers = jdbc.queryForObject(
                "SELECT COUNT(*) FROM pilot_handover WHERE plan_id = ?", Integer.class, planX);
        assertThat(handovers).isZero();
        assertThat(pilotLock(planX, 1L)).containsExactly(DAY, DAY.plusHours(4));
        assertThat(pilotLock(planX, 2L)).isNull();
        assertThat(currentPilot(planX)).isEqualTo(1L);
    }

    @Test
    @DisplayName("同计划只允许一条未结束交班")
    void singleOpenHandoverPerPlan() {
        long planId = executingPlan("SO", 1L, List.of(1L), DAY, DAY.plusHours(4));
        handoverService.apply(new HandoverApplyRequest("SO-ho1", planId, 1L, 2L, DAY.plusHours(2)));
        assertThatThrownBy(() -> handoverService.apply(
                new HandoverApplyRequest("SO-ho2", planId, 1L, 3L, DAY.plusHours(3))))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("未结束交班");
    }

    @Test
    @DisplayName("撤回：切换前释放接班预占而保留原人，撤回后可重新申请")
    void withdrawReleasesPreoccupation() {
        long planId = executingPlan("WD", 1L, List.of(1L), DAY, DAY.plusHours(4));
        long handoverId = handoverService.apply(
                new HandoverApplyRequest("WD-ho", planId, 1L, 2L, DAY.plusHours(2))).handoverId();

        HandoverResponse withdrawn = handoverService.withdraw(
                new HandoverWithdrawRequest("WD-wd", handoverId, 1L));
        assertThat(withdrawn.status()).isEqualTo("WITHDRAWN");
        assertThat(withdrawn.version()).isEqualTo(2);
        // 接班预占释放，原引航员占用保留
        assertThat(pilotLock(planId, 2L)).isNull();
        assertThat(pilotLock(planId, 1L)).containsExactly(DAY, DAY.plusHours(4));
        assertThat(currentPilot(planId)).isEqualTo(1L);

        // 撤回后可重新申请交班
        HandoverResponse reapplied = handoverService.apply(
                new HandoverApplyRequest("WD-ho2", planId, 1L, 3L, DAY.plusHours(3)));
        assertThat(reapplied.status()).isEqualTo("OPEN");
        assertThat(reapplied.inPilotId()).isEqualTo(3L);
    }

    @Test
    @DisplayName("核销关闭未结束交班并释放预占，核销后不可再切入新引航员")
    void settleClosesOpenHandover() {
        long planId = executingPlan("ST", 1L, List.of(1L), DAY, DAY.plusHours(4));
        long handoverId = handoverService.apply(
                new HandoverApplyRequest("ST-ho", planId, 1L, 2L, DAY.plusHours(2))).handoverId();
        handoverService.ack(new HandoverAckRequest("ST-ack1", handoverId, 1L, "交", 1L));

        planService.settle(new SettleRequest("ST-settle", planId));
        assertThat(handoverStatus(handoverId)).isEqualTo("CLOSED");
        assertThat(lockCount(planId)).isZero();
        assertThat(jdbc.queryForObject("SELECT status FROM pilot_plan WHERE id = ?",
                String.class, planId)).isEqualTo("SETTLED");

        // 已核销又切入新引航员：拒绝
        assertThatThrownBy(() -> handoverService.ack(
                new HandoverAckRequest("ST-ack2", handoverId, 2L, "接", 2L)))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("已结束");
        assertThat(currentPilot(planId)).isEqualTo(1L);
    }

    @Test
    @DisplayName("幂等：同参重放、异参 409、失败不占键")
    void idempotencySemantics() {
        long planId = executingPlan("I", 1L, List.of(1L), DAY, DAY.plusHours(4));

        // 失败不占键：端点时刻被拒后，同 requestId 修正参数可成功
        assertThatThrownBy(() -> handoverService.apply(
                new HandoverApplyRequest("I-ho", planId, 1L, 2L, DAY)))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).status().value()).isEqualTo(400));
        HandoverResponse applied = handoverService.apply(
                new HandoverApplyRequest("I-ho", planId, 1L, 2L, DAY.plusHours(2)));
        assertThat(applied.status()).isEqualTo("OPEN");

        // 同键同参重放首次结果，不重复落库
        HandoverResponse replayed = handoverService.apply(
                new HandoverApplyRequest("I-ho", planId, 1L, 2L, DAY.plusHours(2)));
        assertThat(replayed.handoverId()).isEqualTo(applied.handoverId());
        Integer handovers = jdbc.queryForObject(
                "SELECT COUNT(*) FROM pilot_handover WHERE request_id = 'I-ho'", Integer.class);
        assertThat(handovers).isEqualTo(1);

        // 同键异参 → 409
        assertThatThrownBy(() -> handoverService.apply(
                new HandoverApplyRequest("I-ho", planId, 1L, 3L, DAY.plusHours(2))))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).status().value()).isEqualTo(409));
    }

    @Test
    @DisplayName("旧改期入口不能绕过交班占用：与接班预占重叠的改期被拒，端点相接可改期")
    void rescheduleCannotBypassHandoverOccupation() {
        long planX = executingPlan("RS", 1L, List.of(1L), DAY, DAY.plusHours(4));
        handoverService.apply(new HandoverApplyRequest("RS-ho", planX, 1L, 2L, DAY.plusHours(2)));

        // P2 另有已确认计划 10:00-12:00
        long planY = planService.apply(new ApplyRequest(
                "RS-applyY", "测试船代", "乙轮", DAY.plusHours(10), DAY.plusHours(12))).planId();
        planService.schedule(new ScheduleRequest("RS-schedY", planY, 2L, List.of(2L),
                DAY.plusHours(10), DAY.plusHours(12)));
        planService.confirm(new ConfirmRequest("RS-confirmY", planY));

        // 改期到 03:00-05:00 与接班预占 [02:00, 04:00) 内部重叠 → 409
        assertThatThrownBy(() -> planService.reschedule(new RescheduleRequest(
                "RS-re1", planY, DAY.plusHours(3), DAY.plusHours(5),
                DAY.plusHours(3), DAY.plusHours(5))))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).status().value()).isEqualTo(409));

        // 改期到 04:00-06:00 与预占端点相接 → 成功
        planService.reschedule(new RescheduleRequest(
                "RS-re2", planY, DAY.plusHours(4), DAY.plusHours(6),
                DAY.plusHours(4), DAY.plusHours(6)));
        assertThat(jdbc.queryForObject("SELECT plan_start FROM pilot_plan WHERE id = ?",
                java.sql.Timestamp.class, planY).toLocalDateTime()).isEqualTo(DAY.plusHours(4));
    }

    @Test
    @DisplayName("非执行中计划不可发起交班，出班员须为当前负责引航员")
    void applyGuardsOnPlanStateAndOutPilot() {
        // 已确认未执行的计划
        long planId = planService.apply(new ApplyRequest(
                "G-apply", "测试船代", "合成轮", DAY, DAY.plusHours(4))).planId();
        planService.schedule(new ScheduleRequest("G-sched", planId, 1L, List.of(1L),
                DAY.minusHours(1), DAY.plusHours(5)));
        planService.confirm(new ConfirmRequest("G-confirm", planId));
        assertThatThrownBy(() -> handoverService.apply(
                new HandoverApplyRequest("G-ho1", planId, 1L, 2L, DAY.plusHours(2))))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("执行中");

        planService.start(new StartRequest("G-start", planId));
        // 出班员与当前负责引航员不一致 → 400
        assertThatThrownBy(() -> handoverService.apply(
                new HandoverApplyRequest("G-ho2", planId, 2L, 3L, DAY.plusHours(2))))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("不一致");
        // 接班员与出班员相同 → 400
        assertThatThrownBy(() -> handoverService.apply(
                new HandoverApplyRequest("G-ho3", planId, 1L, 1L, DAY.plusHours(2))))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("不同");
    }
}
