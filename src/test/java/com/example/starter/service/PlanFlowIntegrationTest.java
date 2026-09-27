package com.example.starter.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import com.example.starter.AbstractIntegrationTest;
import com.example.starter.dto.OrderDetailResponse;
import com.example.starter.dto.OrderReceiveRequest;
import com.example.starter.dto.OrderReceiveResponse;
import com.example.starter.dto.PlanBaseEvidenceResponse;
import com.example.starter.dto.PlanPublishRequest;
import com.example.starter.dto.PlanPublishRequest.TurbinePlan;
import com.example.starter.dto.PlanPublishResponse;
import com.example.starter.dto.PlanVersionResponse;
import com.example.starter.dto.ReportRequest;
import com.example.starter.web.ApiBadRequestException;
import com.example.starter.web.ApiConflictException;
import com.example.starter.web.ApiNotFoundException;
import com.example.starter.web.ApiUnprocessableException;

/**
 * H2 事务集成测试：日前计划发布/版本查询/整版校验/幂等，计划绑定指令的基数冻结、
 * 过期版本拒绝、目标不可实现 422、新计划不重算已受理指令、超时重分摊引用原计划。
 */
@SpringBootTest
class PlanFlowIntegrationTest extends AbstractIntegrationTest {

    private static final LocalDate PLAN_DATE = LocalDate.of(2026, 9, 27);
    /** 时钟固定在 2026-09-26 10:00 +08:00 = 02:00 UTC，对应该日第 9 个十五分钟半开时段。 */
    private static final int PERIOD = 9;

    @Autowired
    private PlanService planService;

    @Autowired
    private DispatchService dispatchService;

    @BeforeEach
    void setUp() {
        resetDb();
        pinClock(10, 0, 0);
    }

    private List<Integer> periods(int constant) {
        List<Integer> values = new ArrayList<>(PlanService.PERIODS_PER_DAY);
        for (int i = 0; i < PlanService.PERIODS_PER_DAY; i++) {
            values.add(constant);
        }
        return values;
    }

    private List<Integer> periodsWith(int periodIndex, int value, int otherValue) {
        List<Integer> values = periods(otherValue);
        values.set(periodIndex - 1, value);
        return values;
    }

    /** 三台机组 96 段常值计划：默认上限 1800/1500/2000，低于实时可用 2000/1600/2400。 */
    private PlanPublishRequest fullPlan(String requestId, int expectedVersion,
                                        int kw01, int kw02, int kw03) {
        return new PlanPublishRequest(requestId, FARM, PLAN_DATE, expectedVersion, List.of(
                new TurbinePlan("T-01", periods(kw01)),
                new TurbinePlan("T-02", periods(kw02)),
                new TurbinePlan("T-03", periods(kw03))));
    }

    @Test
    @DisplayName("首次发布：版本从 1 开始，形成不可变版本与当前指针，明细为 96×3")
    void firstPublishStartsVersionAtOne() {
        PlanPublishResponse resp = planService.publish(fullPlan("plan-1", 0, 1800, 1500, 2000));

        assertThat(resp.version()).isEqualTo(1);
        assertThat(resp.replayed()).isFalse();
        assertThat(resp.turbineCount()).isEqualTo(3);
        assertThat(resp.periodCount()).isEqualTo(96);

        PlanVersionResponse current = planService.currentPlan(FARM, PLAN_DATE);
        assertThat(current.version()).isEqualTo(1);
        assertThat(current.current()).isTrue();
        assertThat(current.items()).hasSize(96 * 3);
        assertThat(planService.planVersion(FARM, PLAN_DATE, 1).items()).hasSize(288);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM day_ahead_plan WHERE is_current = 'Y'", Integer.class))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("再次发布：版本加 1，原子替换当前指针，历史版本保留且标记为非当前")
    void secondPublishIncrementsVersionAndKeepsHistory() {
        planService.publish(fullPlan("plan-v1", 0, 1800, 1500, 2000));
        PlanPublishResponse v2 = planService.publish(fullPlan("plan-v2", 1, 1700, 1400, 1900));

        assertThat(v2.version()).isEqualTo(2);
        assertThat(planService.currentPlan(FARM, PLAN_DATE).version()).isEqualTo(2);
        PlanVersionResponse history = planService.planVersion(FARM, PLAN_DATE, 1);
        assertThat(history.current()).isFalse();
        assertThat(history.items().get(0).plannedKw()).isEqualTo(1800);
        assertThat(planService.planVersion(FARM, PLAN_DATE, 2).current()).isTrue();
        // 同场同日始终只有一个当前版本，两个历史版本均保留。
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM day_ahead_plan", Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM day_ahead_plan WHERE is_current = 'Y'", Integer.class))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("expectedVersion 不匹配：409；失败不占键，用同键同参修正后成功")
    void expectedVersionMismatchConflictAndRetry() {
        planService.publish(fullPlan("plan-v1", 0, 1800, 1500, 2000));

        assertThatThrownBy(() -> planService.publish(fullPlan("plan-stale", 0, 1700, 1400, 1900)))
                .isInstanceOf(ApiConflictException.class);

        // 同键以正确期望版本重试：首次受理成功而非 409。
        PlanPublishResponse retry = planService.publish(fullPlan("plan-stale", 1, 1700, 1400, 1900));
        assertThat(retry.version()).isEqualTo(2);
        assertThat(retry.replayed()).isFalse();
    }

    @Test
    @DisplayName("发布幂等：同键同参重放首次结果，异参 409")
    void publishIdempotentReplayAndConflict() {
        PlanPublishRequest first = fullPlan("plan-idem", 0, 1800, 1500, 2000);
        PlanPublishResponse r1 = planService.publish(first);
        PlanPublishResponse r2 = planService.publish(first);

        assertThat(r2.replayed()).isTrue();
        assertThat(r2.planId()).isEqualTo(r1.planId());
        assertThat(r2.version()).isEqualTo(1);

        PlanPublishRequest changed = fullPlan("plan-idem", 0, 1800, 1500, 1999);
        assertThatThrownBy(() -> planService.publish(changed))
                .isInstanceOf(ApiConflictException.class);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM day_ahead_plan", Integer.class)).isEqualTo(1);
    }

    @Test
    @DisplayName("整版校验失败：缺段、重复机组、超装机容量、机组未覆盖均 400，且不占幂等键")
    void invalidWholePlanRejected() {
        // 缺段：只有 95 个时段。
        PlanPublishRequest missingPeriod = new PlanPublishRequest("bad-1", FARM, PLAN_DATE, 0, List.of(
                new TurbinePlan("T-01", periods(1800).subList(0, 95)),
                new TurbinePlan("T-02", periods(1500)),
                new TurbinePlan("T-03", periods(2000))));
        assertThatThrownBy(() -> planService.publish(missingPeriod))
                .isInstanceOf(ApiBadRequestException.class);

        // 重复机组。
        PlanPublishRequest duplicate = new PlanPublishRequest("bad-2", FARM, PLAN_DATE, 0, List.of(
                new TurbinePlan("T-01", periods(1800)),
                new TurbinePlan("T-01", periods(1500)),
                new TurbinePlan("T-03", periods(2000))));
        assertThatThrownBy(() -> planService.publish(duplicate))
                .isInstanceOf(ApiBadRequestException.class);

        // 超过合成装机容量（T-03 额定 3000，时段 10 给 3001）。
        PlanPublishRequest overCapacity = new PlanPublishRequest("bad-3", FARM, PLAN_DATE, 0, List.of(
                new TurbinePlan("T-01", periods(1800)),
                new TurbinePlan("T-02", periods(1500)),
                new TurbinePlan("T-03", periodsWith(10, 3001, 2000))));
        assertThatThrownBy(() -> planService.publish(overCapacity))
                .isInstanceOf(ApiBadRequestException.class);

        // 未覆盖全场机组（只报 2 台）。
        PlanPublishRequest missingCoverage = new PlanPublishRequest("bad-4", FARM, PLAN_DATE, 0,
                List.of(
                        new TurbinePlan("T-01", periods(1800)),
                        new TurbinePlan("T-02", periods(1500))));
        assertThatThrownBy(() -> planService.publish(missingCoverage))
                .isInstanceOf(ApiBadRequestException.class);

        // 未知机组不属于风场。
        PlanPublishRequest unknownTurbine = new PlanPublishRequest("bad-5", FARM, PLAN_DATE, 0, List.of(
                new TurbinePlan("T-01", periods(1800)),
                new TurbinePlan("T-02", periods(1500)),
                new TurbinePlan("NOPE", periods(2000))));
        assertThatThrownBy(() -> planService.publish(unknownTurbine))
                .isInstanceOf(ApiBadRequestException.class);

        // 负值计划。
        PlanPublishRequest negative = new PlanPublishRequest("bad-6", FARM, PLAN_DATE, 0, List.of(
                new TurbinePlan("T-01", periodsWith(10, -1, 1800)),
                new TurbinePlan("T-02", periods(1500)),
                new TurbinePlan("T-03", periods(2000))));
        assertThatThrownBy(() -> planService.publish(negative))
                .isInstanceOf(ApiBadRequestException.class);

        // 全部失败随事务回滚：无版本、无明细、幂等键均未被占用。
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM day_ahead_plan", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM day_ahead_plan_item", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM idempotent_record", Integer.class)).isZero();

        // 失败键可用于随后的合法发布。
        PlanPublishResponse afterFix = planService.publish(
                new PlanPublishRequest("bad-1", FARM, PLAN_DATE, 0, List.of(
                        new TurbinePlan("T-01", periods(1800)),
                        new TurbinePlan("T-02", periods(1500)),
                        new TurbinePlan("T-03", periods(2000)))));
        assertThat(afterFix.version()).isEqualTo(1);
    }

    @Test
    @DisplayName("风场不存在：发布 404；版本查询无已发布版本：404；不存在版本号：404")
    void missingFarmAndVersionAre404() {
        PlanPublishRequest wrongFarm = new PlanPublishRequest("p-x", "NO-FARM", PLAN_DATE, 0,
                List.of());
        assertThatThrownBy(() -> planService.publish(wrongFarm))
                .isInstanceOf(ApiNotFoundException.class);

        assertThatThrownBy(() -> planService.currentPlan(FARM, PLAN_DATE))
                .isInstanceOf(ApiNotFoundException.class);

        planService.publish(fullPlan("p-1", 0, 1800, 1500, 2000));
        assertThatThrownBy(() -> planService.planVersion(FARM, PLAN_DATE, 9))
                .isInstanceOf(ApiNotFoundException.class);
    }

    @Test
    @DisplayName("计划绑定指令主流程：可参与出力取 min(实时可用, 核定计划)，基数冻结成证据")
    void planBoundOrderUsesMinOfAvailableAndPlanned() {
        // 计划上限 1800/1500/2000，实时可用 2000/1600/2400 → 可参与 1800/1500/2000 = 5300。
        planService.publish(fullPlan("bp-1", 0, 1800, 1500, 2000));

        OrderReceiveResponse resp = dispatchService.receive(new OrderReceiveRequest(
                "bo-1", FARM, 4500, PLAN_DATE, PERIOD, 1));

        assertThat(resp.farmAvailableKw()).isEqualTo(5300);
        assertThat(resp.curtailedKw()).isEqualTo(800);
        int targetSum = resp.allocations().stream().mapToInt(a -> a.targetPowerKw()).sum();
        assertThat(targetSum).isEqualTo(4500);
        Map<String, Integer> availableByCode = resp.allocations().stream()
                .collect(Collectors.toMap(a -> a.turbineCode(), a -> a.availablePowerKw()));
        assertThat(availableByCode).containsEntry("T-01", 1800)
                .containsEntry("T-02", 1500)
                .containsEntry("T-03", 2000);
        assertThat(resp.allocations()).allSatisfy(a ->
                assertThat(a.targetPowerKw()).isGreaterThanOrEqualTo(a.technicalMinKw()));

        PlanBaseEvidenceResponse evidence = dispatchService.planBaseEvidence(resp.orderNo());
        assertThat(evidence.planVersion()).isEqualTo(1);
        assertThat(evidence.periodIndex()).isEqualTo(PERIOD);
        assertThat(evidence.items()).hasSize(3);
        Map<String, PlanBaseEvidenceResponse.BaseItem> byCode = evidence.items().stream()
                .collect(Collectors.toMap(PlanBaseEvidenceResponse.BaseItem::turbineCode, b -> b));
        assertThat(byCode.get("T-01").plannedKw()).isEqualTo(1800);
        assertThat(byCode.get("T-01").availableKw()).isEqualTo(2000);
        assertThat(byCode.get("T-01").baseKw()).isEqualTo(1800);
        assertThat(byCode.get("T-03").baseKw()).isEqualTo(2000);
    }

    @Test
    @DisplayName("实时可用低于计划时取实时值：小风不被计划抬高")
    void plannedAboveRealAvailableUsesRealValue() {
        // T-03 计划给满 3000，但实时可用仅 2400 → 基数取 2400。
        planService.publish(fullPlan("bp-low", 0, 2000, 1600, 3000));

        OrderReceiveResponse resp = dispatchService.receive(new OrderReceiveRequest(
                "bo-low", FARM, 6000, PLAN_DATE, PERIOD, 1));

        assertThat(resp.farmAvailableKw()).isEqualTo(6000);
        assertThat(resp.curtailedKw()).isZero();
        PlanBaseEvidenceResponse evidence = dispatchService.planBaseEvidence(resp.orderNo());
        var t3 = evidence.items().stream()
                .filter(b -> b.turbineCode().equals("T-03")).findFirst().orElseThrow();
        assertThat(t3.plannedKw()).isEqualTo(3000);
        assertThat(t3.availableKw()).isEqualTo(2400);
        assertThat(t3.baseKw()).isEqualTo(2400);
    }

    @Test
    @DisplayName("计划绑定下目标不可实现：整单 422，指令与基数均不落库，失败不占键")
    void planBoundTargetNotFeasibleRejected422() {
        // 可参与上限合计 100+100+200=400，目标 500 高于可参与出力 → 不可实现。
        planService.publish(fullPlan("bp-422", 0, 100, 100, 200));

        assertThatThrownBy(() -> dispatchService.receive(new OrderReceiveRequest(
                "bo-422", FARM, 500, PLAN_DATE, PERIOD, 1)))
                .isInstanceOf(ApiUnprocessableException.class)
                .hasMessageContaining("高于全场可用出力");

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM curtail_order", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM order_plan_base", Integer.class)).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM idempotent_record WHERE request_id = 'bo-422'", Integer.class))
                .isZero();

        // 失败键修正为可实现目标后可受理（基数合计 400，目标 400 不降机）。
        OrderReceiveResponse retry = dispatchService.receive(new OrderReceiveRequest(
                "bo-422", FARM, 400, PLAN_DATE, PERIOD, 1));
        assertThat(retry.curtailedKw()).isZero();
    }

    @Test
    @DisplayName("过期版本不允许绑定新指令：422；绑定当前版本成功")
    void expiredVersionCannotBind() {
        planService.publish(fullPlan("ep-1", 0, 1800, 1500, 2000));
        planService.publish(fullPlan("ep-2", 1, 1700, 1400, 1900));

        assertThatThrownBy(() -> dispatchService.receive(new OrderReceiveRequest(
                "bo-expired", FARM, 4500, PLAN_DATE, PERIOD, 1)))
                .isInstanceOf(ApiUnprocessableException.class)
                .hasMessageContaining("过期计划版本");

        OrderReceiveResponse current = dispatchService.receive(new OrderReceiveRequest(
                "bo-current", FARM, 4500, PLAN_DATE, PERIOD, 2));
        assertThat(current.farmAvailableKw()).isEqualTo(1700 + 1400 + 1900);
        assertThat(dispatchService.planBaseEvidence(current.orderNo()).planVersion()).isEqualTo(2);
    }

    @Test
    @DisplayName("绑定不存在的版本：404")
    void bindMissingVersionIs404() {
        planService.publish(fullPlan("mp-1", 0, 1800, 1500, 2000));
        assertThatThrownBy(() -> dispatchService.receive(new OrderReceiveRequest(
                "bo-missing", FARM, 4500, PLAN_DATE, PERIOD, 7)))
                .isInstanceOf(ApiNotFoundException.class);
    }

    @Test
    @DisplayName("绑定字段部分提供或时段越界：400")
    void partialBindingFieldsRejected400() {
        planService.publish(fullPlan("pp-1", 0, 1800, 1500, 2000));

        assertThatThrownBy(() -> dispatchService.receive(
                new OrderReceiveRequest("bo-partial", FARM, 4500, PLAN_DATE, null, 1)))
                .isInstanceOf(ApiBadRequestException.class);
        assertThatThrownBy(() -> dispatchService.receive(
                new OrderReceiveRequest("bo-period", FARM, 4500, PLAN_DATE, 97, 1)))
                .isInstanceOf(ApiBadRequestException.class);
        assertThatThrownBy(() -> dispatchService.receive(
                new OrderReceiveRequest("bo-version0", FARM, 4500, PLAN_DATE, PERIOD, 0)))
                .isInstanceOf(ApiBadRequestException.class);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM curtail_order", Integer.class)).isZero();
    }

    @Test
    @DisplayName("受理冻结后发布新计划：已受理指令分摊与基数证据不重算，重放稳定")
    void newPlanDoesNotRecomputeAcceptedOrder() {
        planService.publish(fullPlan("freeze-1", 0, 1800, 1500, 2000));
        OrderReceiveRequest accepted = new OrderReceiveRequest(
                "freeze-order", FARM, 4500, PLAN_DATE, PERIOD, 1);
        OrderReceiveResponse first = dispatchService.receive(accepted);
        List<Integer> frozenTargets = first.allocations().stream()
                .map(a -> a.targetPowerKw()).toList();

        // 受理后发布大幅压低的新版本。
        planService.publish(fullPlan("freeze-2", 1, 500, 500, 600));

        OrderDetailResponse detail = dispatchService.detail(first.orderNo());
        assertThat(detail.cycles()).hasSize(1);
        assertThat(detail.cycles().get(0).allocations()).extracting(a -> a.targetPowerKw())
                .containsExactlyElementsOf(frozenTargets);

        PlanBaseEvidenceResponse evidence = dispatchService.planBaseEvidence(first.orderNo());
        assertThat(evidence.planVersion()).isEqualTo(1);
        assertThat(evidence.items()).extracting(PlanBaseEvidenceResponse.BaseItem::plannedKw)
                .containsExactly(1800, 1500, 2000);

        // 同键同参重放仍返回首次冻结结果。
        OrderReceiveResponse replay = dispatchService.receive(accepted);
        assertThat(replay.replayed()).isTrue();
        assertThat(replay.orderId()).isEqualTo(first.orderId());
        assertThat(replay.allocations()).isEqualTo(first.allocations());
    }

    @Test
    @DisplayName("超时重分摊仍引用原计划上限，仅更新实时可用值；新计划不影响重分摊")
    void timeoutReallocationKeepsOriginalPlanCaps() {
        // 上限 1900/1500/2300，实时初始 2000/1600/2400 → 可参与 5700，目标 5000。
        planService.publish(fullPlan("rt-1", 0, 1900, 1500, 2300));
        OrderReceiveResponse order = dispatchService.receive(new OrderReceiveRequest(
                "rt-order", FARM, 5000, PLAN_DATE, PERIOD, 1));

        // 恰满 10 分钟：T-01 实时可用降到 1800（低于其上限 1900），全场仍高于目标 → 告警重分摊。
        pinClock(10, 10, 0);
        dispatchService.report(new ReportRequest(
                "rt-rp", "T-01", localInstant(10, 10, 0), 1800));

        OrderDetailResponse detail = dispatchService.detail(order.orderNo());
        assertThat(detail.alerts()).hasSize(1);
        assertThat(detail.currentCycleNo()).isEqualTo(2);
        int secondSum = detail.cycles().get(1).allocations().stream()
                .mapToInt(a -> a.targetPowerKw()).sum();
        assertThat(secondSum).isEqualTo(5000);
        // 重分摊基数：T-01=min(1800,1900)=1800、T-02=min(1600,1500)=1500、T-03=min(2400,2300)=2300。
        Map<String, Integer> secondAvailable = detail.cycles().get(1).allocations().stream()
                .collect(Collectors.toMap(a -> a.turbineCode(), a -> a.availablePowerKw()));
        assertThat(secondAvailable).containsEntry("T-01", 1800)
                .containsEntry("T-02", 1500)
                .containsEntry("T-03", 2300);

        // 此后再发布新版本（上限全降），第二轮分摊与冻结证据仍引用原计划。
        planService.publish(fullPlan("rt-2", 1, 400, 400, 500));
        PlanBaseEvidenceResponse evidence = dispatchService.planBaseEvidence(order.orderNo());
        assertThat(evidence.planVersion()).isEqualTo(1);
        assertThat(evidence.items()).extracting(PlanBaseEvidenceResponse.BaseItem::plannedKw)
                .containsExactly(1900, 1500, 2300);
        assertThat(dispatchService.detail(order.orderNo()).cycles()).hasSize(2);
    }

    @Test
    @DisplayName("未绑定计划的原指令行为不变：无基数证据返回 404")
    void unboundOrderHasNoPlanBasis() {
        OrderReceiveResponse order = dispatchService.receive(
                new OrderReceiveRequest("plain-1", FARM, 5000));
        assertThat(order.farmAvailableKw()).isEqualTo(6000);

        assertThatThrownBy(() -> dispatchService.planBaseEvidence(order.orderNo()))
                .isInstanceOf(ApiNotFoundException.class);
        assertThatThrownBy(() -> dispatchService.planBaseEvidence("NO-SUCH-ORDER"))
                .isInstanceOf(ApiNotFoundException.class);
    }
}
