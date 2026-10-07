package com.example.starter.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import com.example.starter.AbstractIntegrationTest;
import com.example.starter.dto.OrderReceiveRequest;
import com.example.starter.dto.PlanPublishRequest;
import com.example.starter.dto.ReportRequest;
import com.example.starter.dto.StagedOrderReceiveRequest;
import com.example.starter.dto.StagedOrderReceiveResponse;
import com.example.starter.dto.StagedPlanCancelRequest;
import com.example.starter.dto.StagedPlanDetailResponse;
import com.example.starter.dto.StagedPlanModifyRequest;
import com.example.starter.dto.StagedPlanModifyResponse;
import com.example.starter.dto.StagedReceiptItem;
import com.example.starter.dto.StagedReceiptRequest;
import com.example.starter.dto.StagedReceiptResponse;
import com.example.starter.dto.StagedSlopeInput;
import com.example.starter.web.ApiBadRequestException;
import com.example.starter.web.ApiConflictException;
import com.example.starter.web.ApiNotFoundException;
import com.example.starter.web.ApiUnprocessableException;

/**
 * H2 事务集成测试：受控分段执行模式。
 * 登记（期限恰等/不足全单拒绝/起始值边界）、分钟回执（整分钟确认、缺项零更新、跳号、
 * 提前执行、冻结阶梯比对）、机组失效暂停与取消释放占用、修改版本控制、
 * 旧回执入口拦截、超时重分配重排守斜率剩余计划、旧指令行为不变。
 */
@SpringBootTest
class StagedOrderFlowIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private DispatchService dispatchService;

    @Autowired
    private StagedPlanService stagedPlanService;

    @Autowired
    private PlanService planService;

    @BeforeEach
    void setUp() {
        resetDb();
        pinClock(10, 0, 0);
    }

    /** 场景 A：T-01 可用 1000、T-02/T-03 压到技术下限，目标 1550 → 仅 T-01 压减 200 到 800。 */
    private void seedAvailable1000() {
        jdbc.update("UPDATE wind_turbine SET available_power_kw = 1000 WHERE turbine_code = 'T-01'");
        jdbc.update("UPDATE wind_turbine SET available_power_kw = 300 WHERE turbine_code = 'T-02'");
        jdbc.update("UPDATE wind_turbine SET available_power_kw = 450 WHERE turbine_code = 'T-03'");
    }

    /** 场景 A 登记：T-01 起始 1000、上限 100kW/分钟、期限 2 分钟（恰等所需 2 分钟）。 */
    private StagedOrderReceiveRequest scenarioARequest(String requestId) {
        return new StagedOrderReceiveRequest(requestId, FARM, 1550, localInstant(10, 0, 0),
                null, null, null,
                List.of(new com.example.starter.dto.StagedTurbineInput("T-01", 1000, 100, 2)));
    }

    private StagedOrderReceiveResponse registerScenarioA(String requestId) {
        seedAvailable1000();
        return dispatchService.receiveStagedOrder(scenarioARequest(requestId));
    }

    @Test
    @DisplayName("登记主流程：1000→800、上限100kW/分钟、期限恰等2分钟，阶梯 900/800 冻结")
    void registerMainFlowDeadlineExactlyMet() {
        StagedOrderReceiveResponse resp = registerScenarioA("so-1");

        assertThat(resp.status()).isEqualTo("DISPATCHED");
        assertThat(resp.planVersion()).isEqualTo(1);
        assertThat(resp.planStatus()).isEqualTo("EXECUTING");
        assertThat(resp.durationMinutes()).isEqualTo(2);
        assertThat(resp.replayed()).isFalse();
        assertThat(resp.turbines()).hasSize(1);
        var tv = resp.turbines().get(0);
        assertThat(tv.turbineCode()).isEqualTo("T-01");
        assertThat(tv.startPowerKw()).isEqualTo(1000);
        assertThat(tv.targetPowerKw()).isEqualTo(800);
        assertThat(tv.neededMinutes()).isEqualTo(2);

        // 冻结阶梯：第 1 分钟 900，第 2 分钟恰 800。
        List<Map<String, Object>> steps = jdbc.queryForList(
                "SELECT minute_no, power_kw FROM staged_plan_step WHERE plan_id = ? ORDER BY minute_no",
                resp.planId());
        assertThat(steps).hasSize(2);
        assertThat(((Number) steps.get(0).get("POWER_KW")).intValue()).isEqualTo(900);
        assertThat(((Number) steps.get(1).get("POWER_KW")).intValue()).isEqualTo(800);

        // 机组占用标记唯一生效。
        Integer occupied = jdbc.queryForObject(
                "SELECT COUNT(*) FROM staged_plan_turbine WHERE occupied_flag = 'Y'", Integer.class);
        assertThat(occupied).isEqualTo(1);

        // 分摊明细：T-01 目标 800，T-02/T-03 保持可用（可降空间为 0）。
        var alloc = jdbc.queryForMap(
                "SELECT target_power_kw FROM allocation a JOIN wind_turbine t ON a.turbine_id = t.id "
                        + "WHERE t.turbine_code = 'T-01'");
        assertThat(((Number) alloc.get("TARGET_POWER_KW")).intValue()).isEqualTo(800);

        // 详情可复算：两分钟阶梯与确认状态。
        StagedPlanDetailResponse detail = stagedPlanService.detail(resp.orderNo());
        assertThat(detail.minutes()).hasSize(2);
        assertThat(detail.minutes().get(0).items().get(0).powerKw()).isEqualTo(900);
        assertThat(detail.minutes().get(1).items().get(0).powerKw()).isEqualTo(800);
        assertThat(detail.minutes()).allSatisfy(m -> assertThat(m.confirmed()).isFalse());
        assertThat(detail.minutes().get(0).minuteAt())
                .isEqualTo(resp.startAt().plusSeconds(60));
    }

    @Test
    @DisplayName("期限不足：任一机组期限内无法到达则全单拒绝（422），不写部分计划、失败不占键")
    void deadlineInfeasibleRejectsWholeOrder() {
        seedAvailable1000();
        // 上限 50kW/分钟、期限 2 分钟：需 4 分钟 > 2 分钟。
        var bad = new StagedOrderReceiveRequest("so-bad", FARM, 1550, localInstant(10, 0, 0),
                null, null, null,
                List.of(new com.example.starter.dto.StagedTurbineInput("T-01", 1000, 50, 2)));
        assertThatThrownBy(() -> dispatchService.receiveStagedOrder(bad))
                .isInstanceOf(ApiUnprocessableException.class)
                .hasMessageContaining("期限内无法到达");

        // 全单拒绝：无指令、无计划、无占用、无幂等占位。
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM curtail_order", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM staged_plan", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM staged_plan_turbine", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM staged_plan_step", Integer.class)).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM idempotent_record WHERE request_id = 'so-bad'",
                Integer.class)).isZero();

        // 失败不占键：同键改合法斜率后受理成功。
        var ok = new StagedOrderReceiveRequest("so-bad", FARM, 1550, localInstant(10, 0, 0),
                null, null, null,
                List.of(new com.example.starter.dto.StagedTurbineInput("T-01", 1000, 100, 2)));
        assertThat(dispatchService.receiveStagedOrder(ok).replayed()).isFalse();
    }

    @Test
    @DisplayName("起始值越界：低于原最低保留或高于当时可用出力均 422")
    void startPowerOutOfRangeRejected() {
        seedAvailable1000();
        // T-01 可用 1000、技术下限 300：起始 200 低于最低保留。
        var tooLow = new StagedOrderReceiveRequest("so-low", FARM, 1550, localInstant(10, 0, 0),
                null, null, null,
                List.of(new com.example.starter.dto.StagedTurbineInput("T-01", 200, 100, 5)));
        assertThatThrownBy(() -> dispatchService.receiveStagedOrder(tooLow))
                .isInstanceOf(ApiUnprocessableException.class)
                .hasMessageContaining("原最低保留");

        // 起始 1200 高于当时可用出力 1000。
        var tooHigh = new StagedOrderReceiveRequest("so-high", FARM, 1550, localInstant(10, 0, 0),
                null, null, null,
                List.of(new com.example.starter.dto.StagedTurbineInput("T-01", 1200, 100, 5)));
        assertThatThrownBy(() -> dispatchService.receiveStagedOrder(tooHigh))
                .isInstanceOf(ApiUnprocessableException.class)
                .hasMessageContaining("当时可用出力");
    }

    @Test
    @DisplayName("回执主流程：逐分钟整分钟确认，最后一分钟完成指令并释放占用")
    void receiptFlowCompletesOrder() {
        StagedOrderReceiveResponse order = registerScenarioA("so-2");

        // 提前执行：10:00:30 确认第 1 分钟（执行时刻 10:01:00）→ 409。
        pinClock(10, 0, 30);
        assertThatThrownBy(() -> stagedPlanService.confirmMinute(new StagedReceiptRequest(
                "sr-early", order.orderNo(), 1, List.of(new StagedReceiptItem("T-01", 900)))))
                .isInstanceOf(ApiConflictException.class)
                .hasMessageContaining("不能提前执行");

        // 第 1 分钟：10:01:00 确认 900。
        pinClock(10, 1, 0);
        StagedReceiptResponse r1 = stagedPlanService.confirmMinute(new StagedReceiptRequest(
                "sr-1", order.orderNo(), 1, List.of(new StagedReceiptItem("T-01", 900))));
        assertThat(r1.executed()).isTrue();
        assertThat(r1.completed()).isFalse();
        assertThat(r1.currentMinute()).isEqualTo(1);
        assertThat(r1.minuteAt().toString()).isEqualTo("2026-09-26T02:01:00Z");
        assertThat(jdbc.queryForObject(
                "SELECT available_power_kw FROM wind_turbine WHERE turbine_code = 'T-01'",
                Integer.class)).isEqualTo(900);

        // 第 2 分钟（最后）：10:02:00 确认 800 → 指令完成。
        pinClock(10, 2, 0);
        StagedReceiptResponse r2 = stagedPlanService.confirmMinute(new StagedReceiptRequest(
                "sr-2", order.orderNo(), 2, List.of(new StagedReceiptItem("T-01", 800))));
        assertThat(r2.executed()).isTrue();
        assertThat(r2.completed()).isTrue();

        var orderRow = jdbc.queryForMap(
                "SELECT status, active_flag FROM curtail_order WHERE id = ?", order.orderId());
        assertThat((String) orderRow.get("STATUS")).isEqualTo("COMPLETED");
        assertThat(orderRow.get("ACTIVE_FLAG")).isNull();
        assertThat(jdbc.queryForObject(
                "SELECT status FROM staged_plan WHERE id = ?", String.class, order.planId()))
                .isEqualTo("COMPLETED");
        // 占用释放。
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM staged_plan_turbine WHERE occupied_flag = 'Y'",
                Integer.class)).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT available_power_kw FROM wind_turbine WHERE turbine_code = 'T-01'",
                Integer.class)).isEqualTo(800);
    }

    @Test
    @DisplayName("缺项回滚：回执缺一机组零更新——实际功率、进度、确认记录全部不变，失败不占键")
    void missingItemZeroUpdate() {
        // 场景 B：T-01 1000→825、T-02 800→675，均上限 100、期限 2。
        jdbc.update("UPDATE wind_turbine SET available_power_kw = 1000 WHERE turbine_code = 'T-01'");
        jdbc.update("UPDATE wind_turbine SET available_power_kw = 800 WHERE turbine_code = 'T-02'");
        jdbc.update("UPDATE wind_turbine SET available_power_kw = 450 WHERE turbine_code = 'T-03'");
        StagedOrderReceiveResponse order = dispatchService.receiveStagedOrder(
                new StagedOrderReceiveRequest("so-3", FARM, 1950, localInstant(10, 0, 0),
                        null, null, null,
                        List.of(new com.example.starter.dto.StagedTurbineInput("T-01", 1000, 100, 2),
                                new com.example.starter.dto.StagedTurbineInput("T-02", 800, 100, 2))));

        pinClock(10, 1, 0);
        // 缺 T-02：整分钟不确认。
        assertThatThrownBy(() -> stagedPlanService.confirmMinute(new StagedReceiptRequest(
                "sr-miss", order.orderNo(), 1, List.of(new StagedReceiptItem("T-01", 900)))))
                .isInstanceOf(ApiUnprocessableException.class)
                .hasMessageContaining("缺一机组零更新");

        // 零更新：功率、进度、确认记录、幂等键全部未变。
        assertThat(jdbc.queryForObject(
                "SELECT available_power_kw FROM wind_turbine WHERE turbine_code = 'T-01'",
                Integer.class)).isEqualTo(1000);
        assertThat(jdbc.queryForObject(
                "SELECT available_power_kw FROM wind_turbine WHERE turbine_code = 'T-02'",
                Integer.class)).isEqualTo(800);
        assertThat(jdbc.queryForObject(
                "SELECT current_minute FROM staged_plan WHERE id = ?",
                Integer.class, order.planId())).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM staged_minute_confirm", Integer.class)).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM idempotent_record WHERE request_id = 'sr-miss'",
                Integer.class)).isZero();

        // 给齐两台后第 1 分钟确认成功。
        StagedReceiptResponse ok = stagedPlanService.confirmMinute(new StagedReceiptRequest(
                "sr-full", order.orderNo(), 1,
                List.of(new StagedReceiptItem("T-01", 900), new StagedReceiptItem("T-02", 700))));
        assertThat(ok.executed()).isTrue();
        assertThat(jdbc.queryForObject(
                "SELECT available_power_kw FROM wind_turbine WHERE turbine_code = 'T-02'",
                Integer.class)).isEqualTo(700);
    }

    @Test
    @DisplayName("跳号与重复序号：须严格按进度+1 确认；同键重放返回首次结果")
    void skipAndDuplicateMinuteRejected() {
        StagedOrderReceiveResponse order = registerScenarioA("so-4");
        pinClock(10, 2, 0);

        // 跳号：直接确认第 2 分钟 → 409。
        assertThatThrownBy(() -> stagedPlanService.confirmMinute(new StagedReceiptRequest(
                "sr-jump", order.orderNo(), 2, List.of(new StagedReceiptItem("T-01", 800)))))
                .isInstanceOf(ApiConflictException.class)
                .hasMessageContaining("跳号");

        // 第 1 分钟确认成功。
        StagedReceiptResponse first = stagedPlanService.confirmMinute(new StagedReceiptRequest(
                "sr-m1", order.orderNo(), 1, List.of(new StagedReceiptItem("T-01", 900))));
        assertThat(first.executed()).isTrue();

        // 同键同参重放：返回首次结果，不重复更新。
        StagedReceiptResponse replay = stagedPlanService.confirmMinute(new StagedReceiptRequest(
                "sr-m1", order.orderNo(), 1, List.of(new StagedReceiptItem("T-01", 900))));
        assertThat(replay.replayed()).isTrue();
        assertThat(replay.currentMinute()).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM staged_minute_confirm", Integer.class)).isEqualTo(1);

        // 异参同键：409。
        assertThatThrownBy(() -> stagedPlanService.confirmMinute(new StagedReceiptRequest(
                "sr-m1", order.orderNo(), 1, List.of(new StagedReceiptItem("T-01", 901)))))
                .isInstanceOf(ApiConflictException.class);

        // 新键重复已确认序号：409（当前进度已为 1，须确认第 2 分钟）。
        assertThatThrownBy(() -> stagedPlanService.confirmMinute(new StagedReceiptRequest(
                "sr-dup", order.orderNo(), 1, List.of(new StagedReceiptItem("T-01", 900)))))
                .isInstanceOf(ApiConflictException.class)
                .hasMessageContaining("跳号");
    }

    @Test
    @DisplayName("回执功率须等于冻结阶梯：不等 422 且零更新")
    void powerMustEqualFrozenStep() {
        StagedOrderReceiveResponse order = registerScenarioA("so-5");
        pinClock(10, 1, 0);
        assertThatThrownBy(() -> stagedPlanService.confirmMinute(new StagedReceiptRequest(
                "sr-wrong", order.orderNo(), 1, List.of(new StagedReceiptItem("T-01", 901)))))
                .isInstanceOf(ApiUnprocessableException.class)
                .hasMessageContaining("不等于冻结阶梯");
        assertThat(jdbc.queryForObject(
                "SELECT available_power_kw FROM wind_turbine WHERE turbine_code = 'T-01'",
                Integer.class)).isEqualTo(1000);
        assertThat(jdbc.queryForObject(
                "SELECT current_minute FROM staged_plan WHERE id = ?",
                Integer.class, order.planId())).isZero();
    }

    @Test
    @DisplayName("机组失效：该分钟不执行、计划暂停、历史不回退；取消释放占用且不恢复原功率")
    void unavailableTurbinePausesThenCancelReleases() {
        StagedOrderReceiveResponse order = registerScenarioA("so-6");

        // 第 1 分钟正常确认（900）。
        pinClock(10, 1, 0);
        stagedPlanService.confirmMinute(new StagedReceiptRequest(
                "sr-p1", order.orderNo(), 1, List.of(new StagedReceiptItem("T-01", 900))));

        // 机组失效：第 2 分钟不执行、计划暂停。
        jdbc.update("UPDATE wind_turbine SET status = 'UNAVAILABLE' WHERE turbine_code = 'T-01'");
        pinClock(10, 2, 0);
        StagedReceiptResponse paused = stagedPlanService.confirmMinute(new StagedReceiptRequest(
                "sr-p2", order.orderNo(), 2, List.of(new StagedReceiptItem("T-01", 800))));
        assertThat(paused.executed()).isFalse();
        assertThat(paused.paused()).isTrue();
        assertThat(paused.currentMinute()).isEqualTo(1);

        // 该分钟零更新：功率保持 900，进度保持 1；已执行历史（第 1 分钟确认）不回退。
        assertThat(jdbc.queryForObject(
                "SELECT available_power_kw FROM wind_turbine WHERE turbine_code = 'T-01'",
                Integer.class)).isEqualTo(900);
        assertThat(jdbc.queryForObject(
                "SELECT status FROM staged_plan WHERE id = ?", String.class, order.planId()))
                .isEqualTo("PAUSED");
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM staged_minute_confirm WHERE plan_id = ?",
                Integer.class, order.planId())).isEqualTo(1);

        // 暂停中回执：409。
        assertThatThrownBy(() -> stagedPlanService.confirmMinute(new StagedReceiptRequest(
                "sr-p3", order.orderNo(), 2, List.of(new StagedReceiptItem("T-01", 800)))))
                .isInstanceOf(ApiConflictException.class)
                .hasMessageContaining("暂停");

        // 取消：释放占用，不自动恢复原功率（保持 900）。
        var cancel = stagedPlanService.cancel(order.orderNo(), new StagedPlanCancelRequest("sc-1"));
        assertThat(cancel.planStatus()).isEqualTo("CANCELED");
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM staged_plan_turbine WHERE occupied_flag = 'Y'",
                Integer.class)).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT available_power_kw FROM wind_turbine WHERE turbine_code = 'T-01'",
                Integer.class)).isEqualTo(900);

        // 占用已释放：旧回执入口恢复可用（指令仍执行中）。
        jdbc.update("UPDATE wind_turbine SET status = 'AVAILABLE' WHERE turbine_code = 'T-01'");
        var report = dispatchService.report(
                new ReportRequest("rp-after-cancel", "T-01", localInstant(10, 3, 0), 950));
        assertThat(report.late()).isFalse();
        assertThat(jdbc.queryForObject(
                "SELECT available_power_kw FROM wind_turbine WHERE turbine_code = 'T-01'",
                Integer.class)).isEqualTo(950);
    }

    @Test
    @DisplayName("执行中不可取消（409）；占用中的机组旧回执入口 409，未参与机组不受影响")
    void executingPlanNotCancelableAndOldReportBlocked() {
        StagedOrderReceiveResponse order = registerScenarioA("so-7");

        assertThatThrownBy(() -> stagedPlanService.cancel(
                order.orderNo(), new StagedPlanCancelRequest("sc-early")))
                .isInstanceOf(ApiConflictException.class)
                .hasMessageContaining("暂停中");

        // 占用中的 T-01：旧回执 409（不能绕过阶梯）。
        assertThatThrownBy(() -> dispatchService.report(
                new ReportRequest("rp-blocked", "T-01", localInstant(10, 1, 0), 900)))
                .isInstanceOf(ApiConflictException.class)
                .hasMessageContaining("不能绕过阶梯");

        // 未参与的 T-03：旧回执正常。
        var report = dispatchService.report(
                new ReportRequest("rp-free", "T-03", localInstant(10, 1, 0), 450));
        assertThat(report.late()).isFalse();
    }

    @Test
    @DisplayName("修改：expectedVersion 旧版 409，成功版本加 1 并重排阶梯；同键重放、异参 409")
    void modifyVersionControlAndIdempotency() {
        StagedOrderReceiveResponse order = registerScenarioA("so-8");

        // 旧版：expectedVersion=2 与当前 1 不符 → 409。
        assertThatThrownBy(() -> stagedPlanService.modify(order.orderNo(),
                new StagedPlanModifyRequest("sm-0", 2,
                        List.of(new StagedSlopeInput("T-01", 50, 4)))))
                .isInstanceOf(ApiConflictException.class);

        // 成功：上限改 50、期限 4 → 版本 2，阶梯 950/900/850/800。
        StagedPlanModifyResponse modified = stagedPlanService.modify(order.orderNo(),
                new StagedPlanModifyRequest("sm-1", 1,
                        List.of(new StagedSlopeInput("T-01", 50, 4))));
        assertThat(modified.planVersion()).isEqualTo(2);
        assertThat(modified.durationMinutes()).isEqualTo(4);
        assertThat(modified.turbines().get(0).neededMinutes()).isEqualTo(4);

        List<Map<String, Object>> steps = jdbc.queryForList(
                "SELECT minute_no, power_kw FROM staged_plan_step "
                        + "WHERE plan_id = ? AND plan_version = 2 ORDER BY minute_no",
                order.planId());
        assertThat(steps).hasSize(4);
        assertThat(((Number) steps.get(0).get("POWER_KW")).intValue()).isEqualTo(950);
        assertThat(((Number) steps.get(3).get("POWER_KW")).intValue()).isEqualTo(800);

        // 同键同参重放。
        StagedPlanModifyResponse replay = stagedPlanService.modify(order.orderNo(),
                new StagedPlanModifyRequest("sm-1", 1,
                        List.of(new StagedSlopeInput("T-01", 50, 4))));
        assertThat(replay.replayed()).isTrue();
        assertThat(replay.planVersion()).isEqualTo(2);

        // 同键异参：409。
        assertThatThrownBy(() -> stagedPlanService.modify(order.orderNo(),
                new StagedPlanModifyRequest("sm-1", 1,
                        List.of(new StagedSlopeInput("T-01", 60, 4)))))
                .isInstanceOf(ApiConflictException.class);

        // 版本 1 已过期：409。
        assertThatThrownBy(() -> stagedPlanService.modify(order.orderNo(),
                new StagedPlanModifyRequest("sm-2", 1,
                        List.of(new StagedSlopeInput("T-01", 50, 4)))))
                .isInstanceOf(ApiConflictException.class);

        // 新斜率期限内不可达：422，版本保持 2、失败不占键。
        assertThatThrownBy(() -> stagedPlanService.modify(order.orderNo(),
                new StagedPlanModifyRequest("sm-3", 2,
                        List.of(new StagedSlopeInput("T-01", 10, 2)))))
                .isInstanceOf(ApiUnprocessableException.class)
                .hasMessageContaining("期限内无法到达");
        assertThat(jdbc.queryForObject(
                "SELECT plan_version FROM staged_plan WHERE id = ?",
                Integer.class, order.planId())).isEqualTo(2);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM idempotent_record WHERE request_id = 'sm-3'",
                Integer.class)).isZero();
    }

    @Test
    @DisplayName("超时重分配：告警后重排守斜率的新剩余计划（版本加 1、起始取最新实际功率）")
    void timeoutReallocationReformsRemainingPlan() {
        // T-01 上限 50、期限 15：阶梯 950/900/850/800 后保持。
        seedAvailable1000();
        StagedOrderReceiveResponse order = dispatchService.receiveStagedOrder(
                new StagedOrderReceiveRequest("so-9", FARM, 1550, localInstant(10, 0, 0),
                        null, null, null,
                        List.of(new com.example.starter.dto.StagedTurbineInput("T-01", 1000, 50, 15))));
        assertThat(order.durationMinutes()).isEqualTo(15);

        // 10:11 确认第 1 分钟（950）：恰满 10 分钟未达标 → 告警 + 重分摊 + 重排剩余计划。
        pinClock(10, 11, 0);
        StagedReceiptResponse r1 = stagedPlanService.confirmMinute(new StagedReceiptRequest(
                "sr-t1", order.orderNo(), 1, List.of(new StagedReceiptItem("T-01", 950))));
        assertThat(r1.executed()).isTrue();

        // 告警与第 2 轮分摊：参与出力 950+300+450=1700，目标 1550，T-01 新目标 800。
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM alert_event WHERE order_id = ?",
                Integer.class, order.orderId())).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT current_cycle_no FROM curtail_order WHERE id = ?",
                Integer.class, order.orderId())).isEqualTo(2);

        // 剩余计划重排：版本 2、起始 950（最新实际功率）、目标 800、上限 50 → 3 分钟阶梯 900/850/800。
        var planRow = jdbc.queryForMap(
                "SELECT plan_version, status, current_minute, duration_minutes FROM staged_plan WHERE id = ?",
                order.planId());
        assertThat(((Number) planRow.get("PLAN_VERSION")).intValue()).isEqualTo(2);
        assertThat((String) planRow.get("STATUS")).isEqualTo("EXECUTING");
        assertThat(((Number) planRow.get("CURRENT_MINUTE")).intValue()).isZero();
        assertThat(((Number) planRow.get("DURATION_MINUTES")).intValue()).isEqualTo(3);

        List<Map<String, Object>> steps = jdbc.queryForList(
                "SELECT minute_no, power_kw FROM staged_plan_step "
                        + "WHERE plan_id = ? AND plan_version = 2 ORDER BY minute_no",
                order.planId());
        assertThat(steps).hasSize(3);
        assertThat(((Number) steps.get(0).get("POWER_KW")).intValue()).isEqualTo(900);
        assertThat(((Number) steps.get(1).get("POWER_KW")).intValue()).isEqualTo(850);
        assertThat(((Number) steps.get(2).get("POWER_KW")).intValue()).isEqualTo(800);

        // 新版本第 1 分钟：启动时刻 10:11 + 1 分钟 = 10:12 可确认。
        pinClock(10, 12, 0);
        StagedReceiptResponse r2 = stagedPlanService.confirmMinute(new StagedReceiptRequest(
                "sr-t2", order.orderNo(), 1, List.of(new StagedReceiptItem("T-01", 900))));
        assertThat(r2.executed()).isTrue();
        assertThat(r2.planVersion()).isEqualTo(2);
    }

    @Test
    @DisplayName("计划绑定分段指令：冻结核定基数 min(实时,计划)；超时重分配原日前基数不换")
    void planBoundStagedOrderKeepsOriginalBasis() {
        // 发布 v1：T-01 核定 1500（低于实时 2000），T-02 1600，T-03 2000。
        planService.publish(new PlanPublishRequest("plan-v1", FARM, PLAN_DATE, 1,
                localInstant(9, 0, 0),
                fullSegments((idx, code) -> switch (code) {
                    case "T-01" -> 1500;
                    case "T-02" -> 1600;
                    default -> 2000;
                })));

        // 绑定 v1 第 40 段：T-01 起始 1500（实时 2000 内）、上限 100、期限 10。
        StagedOrderReceiveResponse order = dispatchService.receiveStagedOrder(
                new StagedOrderReceiveRequest("so-10", FARM, 4000, localInstant(10, 0, 0),
                        PLAN_DATE, 40, 1,
                        List.of(new com.example.starter.dto.StagedTurbineInput("T-01", 1500, 100, 10))));

        // 参与出力 1500/1600/2000=5100，目标 4000；T-01 冻结核定 1500、实时 2000。
        var t1 = order.allocations().stream()
                .filter(a -> a.turbineCode().equals("T-01")).findFirst().orElseThrow();
        assertThat(t1.plannedPowerKw()).isEqualTo(1500);
        assertThat(t1.realtimeAvailableKw()).isEqualTo(2000);
        assertThat(t1.availablePowerKw()).isEqualTo(1500);
        int frozenTarget = t1.targetPowerKw();
        assertThat(order.turbines().get(0).targetPowerKw()).isEqualTo(frozenTarget);

        // 发布 v2 不影响已受理指令基数；推进到恰满 10 分钟后确认第 1 分钟触发重分配。
        pinClock(10, 5, 0);
        planService.publish(new PlanPublishRequest("plan-v2", FARM, PLAN_DATE, 2,
                localInstant(10, 5, 0), fullSegments(900)));
        pinClock(10, 11, 0);
        int minute1 = 1500 - 100;
        StagedReceiptResponse r1 = stagedPlanService.confirmMinute(new StagedReceiptRequest(
                "sr-b1", order.orderNo(), 1, List.of(new StagedReceiptItem("T-01", minute1))));
        assertThat(r1.executed()).isTrue();

        // 重分配第 2 轮：核定计划仍引用原 v1 基数 1500（原日前基数不换）。
        var cycle2 = jdbc.queryForMap(
                "SELECT planned_power_kw, realtime_available_kw FROM allocation a "
                        + "JOIN wind_turbine t ON a.turbine_id = t.id "
                        + "WHERE a.order_id = ? AND a.cycle_no = 2 AND t.turbine_code = 'T-01'",
                order.orderId());
        assertThat(((Number) cycle2.get("PLANNED_POWER_KW")).intValue()).isEqualTo(1500);
        // 分段计划已重排为新版本（守斜率剩余阶梯）。
        assertThat(jdbc.queryForObject(
                "SELECT plan_version FROM staged_plan WHERE id = ?",
                Integer.class, order.planId())).isEqualTo(2);
    }

    @Test
    @DisplayName("旧未启用指令保持原行为：无分段计划（回执/详情 404），旧回报与重分摊不变")
    void legacyOrderBehaviorUnchanged() {
        var legacy = dispatchService.receive(new OrderReceiveRequest("legacy-1", FARM, 5000));

        assertThatThrownBy(() -> stagedPlanService.confirmMinute(new StagedReceiptRequest(
                "sr-legacy", legacy.orderNo(), 1, List.of(new StagedReceiptItem("T-01", 1900)))))
                .isInstanceOf(ApiNotFoundException.class)
                .hasMessageContaining("未启用分段执行模式");
        assertThatThrownBy(() -> stagedPlanService.detail(legacy.orderNo()))
                .isInstanceOf(ApiNotFoundException.class);
        assertThatThrownBy(() -> stagedPlanService.cancel(
                legacy.orderNo(), new StagedPlanCancelRequest("sc-legacy")))
                .isInstanceOf(ApiNotFoundException.class);

        // 旧回报正常（无占用拦截）。
        var report = dispatchService.report(
                new ReportRequest("rp-legacy", "T-01", localInstant(10, 1, 0), 1900));
        assertThat(report.late()).isFalse();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM staged_plan", Integer.class)).isZero();
    }

    @Test
    @DisplayName("分段指令被新指令作废/封存：计划取消并释放占用，旧回执恢复可用")
    void supersedeAndSettleReleaseOccupation() {
        StagedOrderReceiveResponse order = registerScenarioA("so-11");

        // 新指令（不同时刻）原子切换：旧分段计划取消、占用释放。
        pinClock(10, 5, 0);
        dispatchService.receive(new OrderReceiveRequest("new-1", FARM, 1500));
        assertThat(jdbc.queryForObject(
                "SELECT status FROM staged_plan WHERE id = ?", String.class, order.planId()))
                .isEqualTo("CANCELED");
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM staged_plan_turbine WHERE occupied_flag = 'Y'",
                Integer.class)).isZero();

        // 占用释放后旧回执可用。
        var report = dispatchService.report(
                new ReportRequest("rp-after", "T-01", localInstant(10, 6, 0), 1000));
        assertThat(report.late()).isFalse();

        // 封存同样释放占用。
        pinClock(10, 7, 0);
        StagedOrderReceiveResponse order2 = registerScenarioA("so-12");
        dispatchService.settle(order2.orderNo());
        assertThat(jdbc.queryForObject(
                "SELECT status FROM staged_plan WHERE id = ?", String.class, order2.planId()))
                .isEqualTo("CANCELED");
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM staged_plan_turbine WHERE occupied_flag = 'Y'",
                Integer.class)).isZero();
    }

    @Test
    @DisplayName("登记幂等：同键同参重放首次冻结结果，异参 409；绑定字段部分缺失 400")
    void registerIdempotencyAndPartialBinding() {
        StagedOrderReceiveResponse first = registerScenarioA("so-13");
        StagedOrderReceiveResponse replay = dispatchService.receiveStagedOrder(
                scenarioARequest("so-13"));
        assertThat(replay.replayed()).isTrue();
        assertThat(replay.orderId()).isEqualTo(first.orderId());
        assertThat(replay.planId()).isEqualTo(first.planId());

        // 异参（不同目标）：409。
        var different = new StagedOrderReceiveRequest("so-13", FARM, 1500, localInstant(10, 0, 0),
                null, null, null,
                List.of(new com.example.starter.dto.StagedTurbineInput("T-01", 1000, 100, 2)));
        assertThatThrownBy(() -> dispatchService.receiveStagedOrder(different))
                .isInstanceOf(ApiConflictException.class);

        // 绑定字段部分缺失：400。
        var partial = new StagedOrderReceiveRequest("so-14", FARM, 1550, localInstant(10, 0, 0),
                PLAN_DATE, null, null,
                List.of(new com.example.starter.dto.StagedTurbineInput("T-01", 1000, 100, 2)));
        assertThatThrownBy(() -> dispatchService.receiveStagedOrder(partial))
                .isInstanceOf(ApiBadRequestException.class)
                .hasMessageContaining("同空或同非空");
    }
}
