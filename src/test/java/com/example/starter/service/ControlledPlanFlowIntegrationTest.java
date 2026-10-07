package com.example.starter.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import com.example.starter.AbstractIntegrationTest;
import com.example.starter.dto.ControlledCancelRequest;
import com.example.starter.dto.ControlledOrderReceiveRequest;
import com.example.starter.dto.ControlledOrderReceiveResponse;
import com.example.starter.dto.ControlledPlanViewResponse;
import com.example.starter.dto.ControlledReplanRequest;
import com.example.starter.dto.ControlledReplanResponse;
import com.example.starter.dto.ControlledTurbineInput;
import com.example.starter.dto.ControlledTurbinePlanItem;
import com.example.starter.dto.MinuteConfirmRequest;
import com.example.starter.dto.MinuteConfirmResponse;
import com.example.starter.dto.MinuteReportItem;
import com.example.starter.dto.OrderReceiveRequest;
import com.example.starter.dto.PlanPublishRequest;
import com.example.starter.dto.ReplanTurbineInput;
import com.example.starter.dto.ReportRequest;
import com.example.starter.web.ApiConflictException;
import com.example.starter.web.ApiUnprocessableException;

/**
 * 受控分段执行模式 H2 事务集成测试：登记冻结阶梯、整分钟回执（缺项回滚/跳号/提前执行/功率不符）、
 * 期限恰等与不可达全单拒绝、机组失效暂停与取消释放、重规划版本、幂等、旧入口拦截、
 * 超时重分配守斜率新剩余计划且原日前基数不换。
 */
@SpringBootTest
class ControlledPlanFlowIntegrationTest extends AbstractIntegrationTest {

    private static final int SEGMENT = 40;

    @Autowired
    private ControlledPlanService controlledPlanService;

    @Autowired
    private DispatchService dispatchService;

    @Autowired
    private PlanService planService;

    @BeforeEach
    void setUp() {
        resetDb();
        pinClock(10, 0, 0);
    }

    /** 演示场景：目标 2953 → 分摊 954/800/1199；T-02 起始 1000、上限 100 kW/分钟、恰需两分钟到 800。 */
    private ControlledOrderReceiveRequest demoRequest(String requestId) {
        return new ControlledOrderReceiveRequest(requestId, FARM, 2953, null, null, null,
                List.of(
                        new ControlledTurbineInput("T-01", 2000, 500, 3),
                        new ControlledTurbineInput("T-02", 1000, 100, 2),
                        new ControlledTurbineInput("T-03", 2400, 500, 3)));
    }

    private Map<String, ControlledTurbinePlanItem> byCode(ControlledOrderReceiveResponse resp) {
        return resp.turbines().stream()
                .collect(Collectors.toMap(ControlledTurbinePlanItem::turbineCode, t -> t));
    }

    private Map<String, ControlledTurbinePlanItem> byCode(ControlledPlanViewResponse view) {
        return view.turbines().stream()
                .collect(Collectors.toMap(ControlledTurbinePlanItem::turbineCode, t -> t));
    }

    /** 按登记响应中冻结的第 minute 分钟阶梯构造整分钟回执。 */
    private MinuteConfirmRequest confirmReq(String requestId, ControlledOrderReceiveResponse resp,
                                            int minute) {
        List<MinuteReportItem> reports = resp.turbines().stream()
                .map(t -> new MinuteReportItem(t.turbineCode(), t.steps().get(minute - 1).powerKw()))
                .toList();
        return new MinuteConfirmRequest(requestId, reports);
    }

    private int availableOf(String turbineCode) {
        Integer v = jdbc.queryForObject(
                "SELECT available_power_kw FROM wind_turbine WHERE turbine_code = ?",
                Integer.class, turbineCode);
        return v == null ? -1 : v;
    }

    @Test
    @DisplayName("登记主流程：目标由原分摊规则决定（954/800/1199），冻结完整阶梯，T-02 恰两分钟 1000→800")
    void registerFreezesAllocationAndStaircase() {
        ControlledOrderReceiveResponse resp = controlledPlanService.register(demoRequest("c-1"));

        assertThat(resp.planVersion()).isEqualTo(1);
        assertThat(resp.durationMinutes()).isEqualTo(3);
        assertThat(resp.status()).isEqualTo("DISPATCHED");
        assertThat(resp.replayed()).isFalse();
        assertThat(resp.farmParticipatingKw()).isEqualTo(6000);
        assertThat(resp.curtailedKw()).isEqualTo(3047);

        Map<String, ControlledTurbinePlanItem> byCode = byCode(resp);
        assertThat(byCode.get("T-01").targetPowerKw()).isEqualTo(954);
        assertThat(byCode.get("T-02").targetPowerKw()).isEqualTo(800);
        assertThat(byCode.get("T-03").targetPowerKw()).isEqualTo(1199);
        // 冻结阶梯：每分钟移动不超过上限，最后恰达各自目标；T-02 提前到达后保持。
        assertThat(byCode.get("T-01").steps().stream().map(s -> s.powerKw()))
                .containsExactly(1500, 1000, 954);
        assertThat(byCode.get("T-02").steps().stream().map(s -> s.powerKw()))
                .containsExactly(900, 800, 800);
        assertThat(byCode.get("T-03").steps().stream().map(s -> s.powerKw()))
                .containsExactly(1900, 1400, 1199);
        // 第 k 分钟计划时刻 = 启动 UTC 时刻 + k 分钟。
        assertThat(byCode.get("T-02").steps().get(0).scheduledAt())
                .isEqualTo(resp.startAt().plusSeconds(60));
        assertThat(byCode.get("T-02").steps().get(2).scheduledAt())
                .isEqualTo(resp.startAt().plusSeconds(180));

        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM controlled_plan WHERE status='EXECUTING'",
                Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM controlled_plan_step", Integer.class)).isEqualTo(9);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM controlled_occupation", Integer.class)).isEqualTo(3);
        assertThat(jdbc.queryForObject(
                "SELECT controlled_flag FROM curtail_order WHERE id = ?",
                String.class, resp.orderId())).isEqualTo("Y");
    }

    @Test
    @DisplayName("整分钟回执主流程：逐分钟确认推进功率与进度，最后一分钟才完成指令并释放占用")
    void confirmFullFlowCompletesOrder() {
        ControlledOrderReceiveResponse resp = controlledPlanService.register(demoRequest("c-2"));

        pinClock(10, 1, 0);
        MinuteConfirmResponse m1 = controlledPlanService.confirmMinute(
                resp.orderNo(), 1, confirmReq("cf-1", resp, 1));
        assertThat(m1.executed()).isTrue();
        assertThat(m1.completed()).isFalse();
        assertThat(m1.confirmedMinute()).isEqualTo(1);
        assertThat(m1.scheduledAt()).isEqualTo(resp.startAt().plusSeconds(60));
        assertThat(availableOf("T-01")).isEqualTo(1500);
        assertThat(availableOf("T-02")).isEqualTo(900);
        assertThat(availableOf("T-03")).isEqualTo(1900);

        pinClock(10, 2, 0);
        controlledPlanService.confirmMinute(resp.orderNo(), 2, confirmReq("cf-2", resp, 2));
        assertThat(availableOf("T-01")).isEqualTo(1000);
        assertThat(availableOf("T-02")).isEqualTo(800);

        pinClock(10, 3, 0);
        MinuteConfirmResponse m3 = controlledPlanService.confirmMinute(
                resp.orderNo(), 3, confirmReq("cf-3", resp, 3));
        assertThat(m3.completed()).isTrue();
        assertThat(m3.orderStatus()).isEqualTo("COMPLETED");
        assertThat(m3.planStatus()).isEqualTo("COMPLETED");
        assertThat(availableOf("T-01")).isEqualTo(954);
        assertThat(availableOf("T-02")).isEqualTo(800);
        assertThat(availableOf("T-03")).isEqualTo(1199);

        assertThat(jdbc.queryForObject(
                "SELECT status FROM curtail_order WHERE id = ?", String.class, resp.orderId()))
                .isEqualTo("COMPLETED");
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM controlled_occupation", Integer.class)).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM controlled_minute_confirm", Integer.class)).isEqualTo(3);
    }

    @Test
    @DisplayName("期限不可达全单拒绝：T-02 只给 1 分钟，不写任何部分计划且失败不占键")
    void infeasibleDurationRejectsWholeOrder() {
        ControlledOrderReceiveRequest bad = new ControlledOrderReceiveRequest("c-bad", FARM, 2953,
                null, null, null,
                List.of(
                        new ControlledTurbineInput("T-01", 2000, 500, 3),
                        new ControlledTurbineInput("T-02", 1000, 100, 1),
                        new ControlledTurbineInput("T-03", 2400, 500, 3)));
        assertThatThrownBy(() -> controlledPlanService.register(bad))
                .isInstanceOf(ApiUnprocessableException.class)
                .hasMessageContaining("无法到达目标");

        // 不先写部分计划：指令、计划、阶梯、占用、幂等键全部为零。
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM curtail_order", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM controlled_plan", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM controlled_plan_step", Integer.class))
                .isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM controlled_occupation", Integer.class))
                .isZero();
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM idempotent_record WHERE request_id = 'c-bad'",
                Integer.class)).isZero();

        // 失败不占键：同键改为期限恰等（2 分钟）后受理成功。
        ControlledOrderReceiveResponse ok = controlledPlanService.register(demoRequest("c-bad"));
        assertThat(ok.replayed()).isFalse();
        assertThat(ok.planVersion()).isEqualTo(1);
    }

    @Test
    @DisplayName("起始功率越界与登记缺项：422 整单拒绝")
    void startOutOfRangeAndMissingTurbineRejected() {
        // 起始高于当时可用出力（T-02 可用 1600）。
        ControlledOrderReceiveRequest tooHigh = new ControlledOrderReceiveRequest("c-hi", FARM, 2953,
                null, null, null,
                List.of(
                        new ControlledTurbineInput("T-01", 2000, 500, 3),
                        new ControlledTurbineInput("T-02", 1601, 100, 2),
                        new ControlledTurbineInput("T-03", 2400, 500, 3)));
        assertThatThrownBy(() -> controlledPlanService.register(tooHigh))
                .isInstanceOf(ApiUnprocessableException.class)
                .hasMessageContaining("不在最低保留");

        // 起始低于最低保留（T-01 技术下限 300）。
        ControlledOrderReceiveRequest tooLow = new ControlledOrderReceiveRequest("c-lo", FARM, 2953,
                null, null, null,
                List.of(
                        new ControlledTurbineInput("T-01", 200, 500, 3),
                        new ControlledTurbineInput("T-02", 1000, 100, 2),
                        new ControlledTurbineInput("T-03", 2400, 500, 3)));
        assertThatThrownBy(() -> controlledPlanService.register(tooLow))
                .isInstanceOf(ApiUnprocessableException.class)
                .hasMessageContaining("不在最低保留");

        // 登记缺项：只覆盖两台机组。
        ControlledOrderReceiveRequest missing = new ControlledOrderReceiveRequest("c-miss", FARM,
                2953, null, null, null,
                List.of(
                        new ControlledTurbineInput("T-01", 2000, 500, 3),
                        new ControlledTurbineInput("T-02", 1000, 100, 2)));
        assertThatThrownBy(() -> controlledPlanService.register(missing))
                .isInstanceOf(ApiUnprocessableException.class)
                .hasMessageContaining("恰好覆盖");

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM curtail_order", Integer.class)).isZero();
    }

    @Test
    @DisplayName("回执缺项零更新：缺一机组整分钟回滚，进度、功率、幂等键均不变")
    void confirmMissingTurbineRollsBack() {
        ControlledOrderReceiveResponse resp = controlledPlanService.register(demoRequest("c-3"));
        pinClock(10, 1, 0);

        // 缺 T-03 的回执：整分钟拒绝。
        MinuteConfirmRequest missing = new MinuteConfirmRequest("cf-miss",
                List.of(new MinuteReportItem("T-01", 1500), new MinuteReportItem("T-02", 900)));
        assertThatThrownBy(() -> controlledPlanService.confirmMinute(resp.orderNo(), 1, missing))
                .isInstanceOf(ApiUnprocessableException.class)
                .hasMessageContaining("缺一机组零更新");

        // 零更新：功率、进度、确认记录、幂等键全部未变。
        assertThat(availableOf("T-01")).isEqualTo(2000);
        assertThat(availableOf("T-02")).isEqualTo(1600);
        assertThat(availableOf("T-03")).isEqualTo(2400);
        assertThat(jdbc.queryForObject(
                "SELECT confirmed_minute FROM controlled_plan", Integer.class)).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM controlled_minute_confirm", Integer.class)).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM idempotent_record WHERE request_id = 'cf-miss'",
                Integer.class)).isZero();

        // 失败不占键：同键补齐三机组后正常确认。
        MinuteConfirmRequest fixed = new MinuteConfirmRequest("cf-miss",
                List.of(new MinuteReportItem("T-01", 1500), new MinuteReportItem("T-02", 900),
                        new MinuteReportItem("T-03", 1900)));
        MinuteConfirmResponse ok = controlledPlanService.confirmMinute(resp.orderNo(), 1, fixed);
        assertThat(ok.executed()).isTrue();
        assertThat(availableOf("T-03")).isEqualTo(1900);
    }

    @Test
    @DisplayName("回执功率须等于冻结阶梯：不符 422 且零更新")
    void confirmPowerMismatchRejected() {
        ControlledOrderReceiveResponse resp = controlledPlanService.register(demoRequest("c-4"));
        pinClock(10, 1, 0);
        MinuteConfirmRequest wrong = new MinuteConfirmRequest("cf-wrong",
                List.of(new MinuteReportItem("T-01", 1500), new MinuteReportItem("T-02", 901),
                        new MinuteReportItem("T-03", 1900)));
        assertThatThrownBy(() -> controlledPlanService.confirmMinute(resp.orderNo(), 1, wrong))
                .isInstanceOf(ApiUnprocessableException.class)
                .hasMessageContaining("不等于冻结阶梯");
        assertThat(availableOf("T-02")).isEqualTo(1600);
        assertThat(jdbc.queryForObject(
                "SELECT confirmed_minute FROM controlled_plan", Integer.class)).isZero();
    }

    @Test
    @DisplayName("不能提前执行或跳号：计划时刻未到 409，跳号/重复确认 409")
    void earlyConfirmAndSkipRejected() {
        ControlledOrderReceiveResponse resp = controlledPlanService.register(demoRequest("c-5"));

        // 10:00:30 早于第 1 分钟计划时刻 10:01:00。
        pinClock(10, 0, 30);
        assertThatThrownBy(() -> controlledPlanService.confirmMinute(
                resp.orderNo(), 1, confirmReq("cf-early", resp, 1)))
                .isInstanceOf(ApiConflictException.class)
                .hasMessageContaining("不能提前执行");

        // 跳号：第 1 分钟未确认直接确认第 2 分钟。
        pinClock(10, 2, 0);
        assertThatThrownBy(() -> controlledPlanService.confirmMinute(
                resp.orderNo(), 2, confirmReq("cf-skip", resp, 2)))
                .isInstanceOf(ApiConflictException.class)
                .hasMessageContaining("不能跳号");

        // 正常确认第 1 分钟后，重复确认第 1 分钟（不同 requestId）同样 409。
        controlledPlanService.confirmMinute(resp.orderNo(), 1, confirmReq("cf-ok1", resp, 1));
        assertThatThrownBy(() -> controlledPlanService.confirmMinute(
                resp.orderNo(), 1, confirmReq("cf-dup", resp, 1)))
                .isInstanceOf(ApiConflictException.class)
                .hasMessageContaining("不能跳号");
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM controlled_minute_confirm", Integer.class)).isEqualTo(1);
    }

    @Test
    @DisplayName("机组失效：该分钟不执行、计划暂停、已执行历史不回退；暂停后取消释放占用且不恢复功率")
    void pauseOnUnavailableThenCancelReleases() {
        ControlledOrderReceiveResponse resp = controlledPlanService.register(demoRequest("c-6"));
        pinClock(10, 1, 0);
        controlledPlanService.confirmMinute(resp.orderNo(), 1, confirmReq("cf-p1", resp, 1));

        jdbc.update("UPDATE wind_turbine SET run_status = 'UNAVAILABLE' WHERE turbine_code = 'T-02'");
        pinClock(10, 2, 0);
        MinuteConfirmResponse paused = controlledPlanService.confirmMinute(
                resp.orderNo(), 2, confirmReq("cf-p2", resp, 2));

        assertThat(paused.executed()).isFalse();
        assertThat(paused.planStatus()).isEqualTo("PAUSED");
        assertThat(paused.confirmedMinute()).isEqualTo(1);
        // 已执行历史不回退：第 1 分钟功率保持，第 2 分钟未应用。
        assertThat(availableOf("T-01")).isEqualTo(1500);
        assertThat(availableOf("T-02")).isEqualTo(900);
        assertThat(availableOf("T-03")).isEqualTo(1900);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM controlled_minute_confirm", Integer.class)).isEqualTo(1);

        // 暂停中不能再确认分钟。
        jdbc.update("UPDATE wind_turbine SET run_status = 'AVAILABLE' WHERE turbine_code = 'T-02'");
        assertThatThrownBy(() -> controlledPlanService.confirmMinute(
                resp.orderNo(), 2, confirmReq("cf-p3", resp, 2)))
                .isInstanceOf(ApiConflictException.class)
                .hasMessageContaining("已暂停");

        // 暂停后可取消：释放占用，不自动恢复原功率。
        var cancel = controlledPlanService.cancel(resp.orderNo(),
                new ControlledCancelRequest("cx-1", 1));
        assertThat(cancel.planStatus()).isEqualTo("CANCELLED");
        assertThat(cancel.orderStatus()).isEqualTo("CANCELLED");
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM controlled_occupation", Integer.class)).isZero();
        assertThat(availableOf("T-01")).isEqualTo(1500);
        assertThat(availableOf("T-02")).isEqualTo(900);

        // 占用已释放：新受控指令可登记（旧指令被原子切换）；起始值取当前实际功率。
        ControlledOrderReceiveResponse next = controlledPlanService.register(
                new ControlledOrderReceiveRequest("c-7", FARM, 2953, null, null, null,
                        List.of(
                                new ControlledTurbineInput("T-01", 1500, 500, 2),
                                new ControlledTurbineInput("T-02", 900, 500, 1),
                                new ControlledTurbineInput("T-03", 1900, 500, 2))));
        assertThat(next.replayed()).isFalse();
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM controlled_occupation", Integer.class)).isEqualTo(3);
    }

    @Test
    @DisplayName("重规划：expectedVersion 成功加 1、旧版 409、不可行 422 且版本不变、失败不占键")
    void replanVersioningAndConflict() {
        ControlledOrderReceiveResponse resp = controlledPlanService.register(demoRequest("c-8"));
        pinClock(10, 1, 0);
        controlledPlanService.confirmMinute(resp.orderNo(), 1, confirmReq("cf-r1", resp, 1));

        // 从当前实际功率（1500/900/1900）重规划到原目标（954/800/1199）。
        ControlledReplanResponse v2 = controlledPlanService.replan(resp.orderNo(),
                new ControlledReplanRequest("rp-1", 1,
                        List.of(new ReplanTurbineInput("T-01", 100, 6),
                                new ReplanTurbineInput("T-02", 100, 1),
                                new ReplanTurbineInput("T-03", 100, 8))));
        assertThat(v2.planVersion()).isEqualTo(2);
        assertThat(v2.durationMinutes()).isEqualTo(8);
        Map<String, ControlledTurbinePlanItem> byCode = v2.turbines().stream()
                .collect(Collectors.toMap(ControlledTurbinePlanItem::turbineCode, t -> t));
        assertThat(byCode.get("T-01").startPowerKw()).isEqualTo(1500);
        assertThat(byCode.get("T-01").targetPowerKw()).isEqualTo(954);
        assertThat(byCode.get("T-01").steps().stream().map(s -> s.powerKw()))
                .containsExactly(1400, 1300, 1200, 1100, 1000, 954, 954, 954);
        assertThat(byCode.get("T-02").steps().stream().map(s -> s.powerKw()))
                .containsExactly(800, 800, 800, 800, 800, 800, 800, 800);

        assertThat(jdbc.queryForObject(
                "SELECT status FROM controlled_plan WHERE plan_version = 1",
                String.class)).isEqualTo("REPLACED");
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM controlled_occupation", Integer.class)).isEqualTo(3);

        // 旧版 409。
        assertThatThrownBy(() -> controlledPlanService.replan(resp.orderNo(),
                new ControlledReplanRequest("rp-2", 1,
                        List.of(new ReplanTurbineInput("T-01", 100, 6),
                                new ReplanTurbineInput("T-02", 100, 1),
                                new ReplanTurbineInput("T-03", 100, 8)))))
                .isInstanceOf(ApiConflictException.class)
                .hasMessageContaining("旧版");

        // 不可行 422：版本不变、失败不占键。
        assertThatThrownBy(() -> controlledPlanService.replan(resp.orderNo(),
                new ControlledReplanRequest("rp-3", 2,
                        List.of(new ReplanTurbineInput("T-01", 100, 1),
                                new ReplanTurbineInput("T-02", 100, 1),
                                new ReplanTurbineInput("T-03", 100, 8)))))
                .isInstanceOf(ApiUnprocessableException.class)
                .hasMessageContaining("无法到达目标");
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM controlled_plan WHERE plan_version = 3",
                Integer.class)).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM idempotent_record WHERE request_id = 'rp-3'",
                Integer.class)).isZero();

        // 同 requestId 同参重放，异参 409。
        ControlledReplanResponse replay = controlledPlanService.replan(resp.orderNo(),
                new ControlledReplanRequest("rp-1", 1,
                        List.of(new ReplanTurbineInput("T-01", 100, 6),
                                new ReplanTurbineInput("T-02", 100, 1),
                                new ReplanTurbineInput("T-03", 100, 8))));
        assertThat(replay.replayed()).isTrue();
        assertThat(replay.planVersion()).isEqualTo(2);
        assertThatThrownBy(() -> controlledPlanService.replan(resp.orderNo(),
                new ControlledReplanRequest("rp-1", 1,
                        List.of(new ReplanTurbineInput("T-01", 100, 7),
                                new ReplanTurbineInput("T-02", 100, 1),
                                new ReplanTurbineInput("T-03", 100, 8)))))
                .isInstanceOf(ApiConflictException.class);
    }

    @Test
    @DisplayName("登记与回执幂等：同键同参重放首次冻结结果，异参 409")
    void idempotentReplayAndConflict() {
        ControlledOrderReceiveResponse first = controlledPlanService.register(demoRequest("c-9"));
        ControlledOrderReceiveResponse replay = controlledPlanService.register(demoRequest("c-9"));
        assertThat(replay.replayed()).isTrue();
        assertThat(replay.orderId()).isEqualTo(first.orderId());
        assertThat(replay.turbines()).isEqualTo(first.turbines());

        ControlledOrderReceiveRequest different = new ControlledOrderReceiveRequest("c-9", FARM,
                2950, null, null, null,
                List.of(
                        new ControlledTurbineInput("T-01", 2000, 500, 3),
                        new ControlledTurbineInput("T-02", 1000, 100, 2),
                        new ControlledTurbineInput("T-03", 2400, 500, 3)));
        assertThatThrownBy(() -> controlledPlanService.register(different))
                .isInstanceOf(ApiConflictException.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM curtail_order", Integer.class))
                .isEqualTo(1);

        pinClock(10, 1, 0);
        MinuteConfirmResponse m1 = controlledPlanService.confirmMinute(
                first.orderNo(), 1, confirmReq("cf-idem", first, 1));
        MinuteConfirmResponse m1Replay = controlledPlanService.confirmMinute(
                first.orderNo(), 1, confirmReq("cf-idem", first, 1));
        assertThat(m1Replay.replayed()).isTrue();
        assertThat(m1Replay.executed()).isEqualTo(m1.executed());
        // 同键异参 409。
        MinuteConfirmRequest altered = new MinuteConfirmRequest("cf-idem",
                List.of(new MinuteReportItem("T-01", 1500), new MinuteReportItem("T-02", 900),
                        new MinuteReportItem("T-03", 1901)));
        assertThatThrownBy(() -> controlledPlanService.confirmMinute(first.orderNo(), 1, altered))
                .isInstanceOf(ApiConflictException.class);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM controlled_minute_confirm", Integer.class)).isEqualTo(1);
    }

    @Test
    @DisplayName("旧回执入口不能绕过受控阶梯：409；旧指令原子切换受控指令并释放占用")
    void legacyReportBlockedAndLegacyOrderSupersedes() {
        ControlledOrderReceiveResponse resp = controlledPlanService.register(demoRequest("c-10"));
        assertThatThrownBy(() -> dispatchService.report(
                new ReportRequest("legacy-rp", "T-01", localInstant(10, 0, 30), 1500)))
                .isInstanceOf(ApiConflictException.class)
                .hasMessageContaining("不能绕过");
        // 旧回执被拦截：功率未被改写。
        assertThat(availableOf("T-01")).isEqualTo(2000);

        // 旧模式新指令（更晚时刻）原子切换：受控计划被替换、占用释放。
        pinClock(10, 5, 0);
        var legacy = dispatchService.receive(new OrderReceiveRequest("legacy-1", FARM, 5000));
        assertThat(legacy.status()).isEqualTo("DISPATCHED");
        assertThat(jdbc.queryForObject(
                "SELECT status FROM curtail_order WHERE id = ?", String.class, resp.orderId()))
                .isEqualTo("SUPERSEDED");
        assertThat(jdbc.queryForObject(
                "SELECT status FROM controlled_plan WHERE order_id = ?",
                String.class, resp.orderId())).isEqualTo("REPLACED");
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM controlled_occupation", Integer.class)).isZero();
    }

    @Test
    @DisplayName("超时重分配：告警后重新形成守斜率新剩余计划，原日前基数不换")
    void timeoutReallocationFormsRampSafeRemainingPlan() {
        // 绑定日前计划 v1（核定=实时可用），受理后发布 v2（核定 100）不影响已受理指令。
        planService.publish(new PlanPublishRequest("plan-v1", FARM, PLAN_DATE, 1,
                localInstant(9, 0, 0),
                fullSegments((idx, code) -> switch (code) {
                    case "T-01" -> 2000;
                    case "T-02" -> 1600;
                    default -> 2400;
                })));
        ControlledOrderReceiveRequest req = new ControlledOrderReceiveRequest("c-11", FARM, 5400,
                PLAN_DATE, SEGMENT, 1,
                List.of(
                        new ControlledTurbineInput("T-01", 2000, 20, 11),
                        new ControlledTurbineInput("T-02", 1600, 20, 8),
                        new ControlledTurbineInput("T-03", 2400, 20, 12)));
        ControlledOrderReceiveResponse resp = controlledPlanService.register(req);
        assertThat(resp.planVersionNo()).isEqualTo(1);
        assertThat(resp.durationMinutes()).isEqualTo(12);
        Map<String, ControlledTurbinePlanItem> registered = byCode(resp);
        assertThat(registered.get("T-01").targetPowerKw()).isEqualTo(1794);
        assertThat(registered.get("T-02").targetPowerKw()).isEqualTo(1443);
        assertThat(registered.get("T-03").targetPowerKw()).isEqualTo(2163);

        pinClock(10, 5, 0);
        planService.publish(new PlanPublishRequest("plan-v2", FARM, PLAN_DATE, 2,
                localInstant(10, 5, 0), fullSegments(100)));

        // 逐分钟确认到第 10 分钟（恰满 10 分钟判定时刻）。
        for (int minute = 1; minute <= 10; minute++) {
            pinClock(10, minute, 0);
            controlledPlanService.confirmMinute(
                    resp.orderNo(), minute, confirmReq("cf-t" + minute, resp, minute));
        }

        // 第 10 分钟确认后：全场 1800/1443/2200=5443 仍高于目标 5400 → 告警并重分摊第 2 轮。
        assertThat(availableOf("T-01")).isEqualTo(1800);
        assertThat(availableOf("T-02")).isEqualTo(1443);
        assertThat(availableOf("T-03")).isEqualTo(2200);
        Map<String, Object> alert = jdbc.queryForMap(
                "SELECT cycle_no, farm_actual_kw, reallocated_cycle_no FROM alert_event");
        assertThat(alert.get("CYCLE_NO")).isEqualTo(1);
        assertThat(alert.get("FARM_ACTUAL_KW")).isEqualTo(5443);
        assertThat(alert.get("REALLOCATED_CYCLE_NO")).isEqualTo(2);
        assertThat(jdbc.queryForObject(
                "SELECT current_cycle_no FROM curtail_order WHERE id = ?",
                Integer.class, resp.orderId())).isEqualTo(2);

        // 第 2 轮分摊：原日前基数（v1 核定 2000/1600/2400）不换，实时值刷新。
        List<Map<String, Object>> cycle2 = jdbc.queryForList("""
                SELECT t.turbine_code AS code, a.planned_power_kw AS planned,
                       a.realtime_available_kw AS realtime, a.target_power_kw AS target
                FROM allocation a JOIN wind_turbine t ON a.turbine_id = t.id
                WHERE a.order_id = ? AND a.cycle_no = 2 ORDER BY t.turbine_code
                """, resp.orderId());
        assertThat(cycle2).hasSize(3);
        assertThat(cycle2.get(0).get("PLANNED")).isEqualTo(2000);
        assertThat(cycle2.get(1).get("PLANNED")).isEqualTo(1600);
        assertThat(cycle2.get(2).get("PLANNED")).isEqualTo(2400);
        assertThat(cycle2.get(0).get("REALTIME")).isEqualTo(1800);
        assertThat(cycle2.get(1).get("REALTIME")).isEqualTo(1443);
        assertThat(cycle2.get(2).get("REALTIME")).isEqualTo(2200);
        assertThat(cycle2.get(0).get("TARGET")).isEqualTo(1786);
        assertThat(cycle2.get(1).get("TARGET")).isEqualTo(1432);
        assertThat(cycle2.get(2).get("TARGET")).isEqualTo(2182);

        // 守斜率新剩余计划：v2 执行中，起始=第 10 分钟实际功率，斜率沿用登记值，目标=新分摊。
        ControlledPlanViewResponse view = controlledPlanService.planView(resp.orderNo());
        assertThat(view.planVersion()).isEqualTo(2);
        assertThat(view.planStatus()).isEqualTo("EXECUTING");
        assertThat(view.confirmedMinute()).isZero();
        Map<String, ControlledTurbinePlanItem> v2 = byCode(view);
        assertThat(v2.get("T-01").startPowerKw()).isEqualTo(1800);
        assertThat(v2.get("T-01").targetPowerKw()).isEqualTo(1786);
        assertThat(v2.get("T-01").rampRateKwPerMin()).isEqualTo(20);
        assertThat(v2.get("T-01").steps().stream().map(s -> s.powerKw()).findFirst())
                .contains(1786);
        assertThat(v2.get("T-03").startPowerKw()).isEqualTo(2200);
        assertThat(v2.get("T-03").targetPowerKw()).isEqualTo(2182);
        assertThat(jdbc.queryForObject(
                "SELECT status FROM controlled_plan WHERE plan_version = 1",
                String.class)).isEqualTo("REPLACED");
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM controlled_occupation", Integer.class)).isEqualTo(3);

        // 新版本从第 1 分钟重新确认：计划时刻 = 新启动时刻 + 1 分钟。
        pinClock(10, 11, 0);
        MinuteConfirmRequest v2m1 = new MinuteConfirmRequest("cf-v2-1",
                List.of(new MinuteReportItem("T-01", 1786), new MinuteReportItem("T-02", 1432),
                        new MinuteReportItem("T-03", 2182)));
        MinuteConfirmResponse confirmed = controlledPlanService.confirmMinute(
                resp.orderNo(), 1, v2m1);
        assertThat(confirmed.executed()).isTrue();
        assertThat(confirmed.planVersion()).isEqualTo(2);
        assertThat(availableOf("T-01")).isEqualTo(1786);
    }

    @Test
    @DisplayName("计划绑定受控指令：可参与出力=min(实时,核定计划)，基数证据可查")
    void planBoundControlledOrder() {
        planService.publish(new PlanPublishRequest("plan-b1", FARM, PLAN_DATE, 1,
                localInstant(9, 0, 0),
                fullSegments((idx, code) -> switch (code) {
                    case "T-01" -> 1500;
                    case "T-02" -> 1600;
                    default -> 2000;
                })));
        ControlledOrderReceiveRequest req = new ControlledOrderReceiveRequest("c-12", FARM, 4000,
                PLAN_DATE, SEGMENT, 1,
                List.of(
                        new ControlledTurbineInput("T-01", 1500, 500, 3),
                        new ControlledTurbineInput("T-02", 1600, 500, 3),
                        new ControlledTurbineInput("T-03", 2000, 500, 3)));
        ControlledOrderReceiveResponse resp = controlledPlanService.register(req);

        // 参与出力 1500/1600/2000=5100，目标 4000。
        assertThat(resp.farmParticipatingKw()).isEqualTo(5100);
        assertThat(resp.planVersionNo()).isEqualTo(1);
        int targetSum = resp.turbines().stream()
                .mapToInt(ControlledTurbinePlanItem::targetPowerKw).sum();
        assertThat(targetSum).isEqualTo(4000);

        var basis = dispatchService.basis(resp.orderNo());
        assertThat(basis.planVersionNo()).isEqualTo(1);
        assertThat(basis.cycles()).hasSize(1);
        assertThat(basis.cycles().get(0).items())
                .allSatisfy(i -> assertThat(i.plannedPowerKw()).isIn(1500, 1600, 2000));
    }
}
