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
import com.example.starter.dto.PlanBindingEvidenceResponse;
import com.example.starter.dto.PlanOrderReceiveRequest;
import com.example.starter.dto.PlanPublishRequest;
import com.example.starter.dto.PlanPublishResponse;
import com.example.starter.dto.ReportRequest;
import com.example.starter.web.ApiConflictException;
import com.example.starter.web.ApiNotFoundException;
import com.example.starter.web.ApiUnprocessableException;

/**
 * 计划绑定模式限电指令 H2 事务集成测试：min(实时可用, 核定计划) 分摊、受理冻结基数及版本、
 * 过期版本/目标不可实现整单拒绝、新计划发布不重算已受理指令、超时重分摊引用原计划、
 * 幂等重放与基数证据查询。
 */
@SpringBootTest
class PlanBoundOrderIntegrationTest extends AbstractIntegrationTest {

    private static final LocalDate DATE = LocalDate.of(2026, 9, 27);

    @Autowired
    private PlanService planService;

    @Autowired
    private DispatchService dispatchService;

    @BeforeEach
    void setUp() {
        resetDb();
        pinClock(10, 0, 0);
    }

    private PlanPublishRequest planWithSlot0(String requestId, int expectedVersion,
                                             int s1, int s2, int s3) {
        List<PlanPublishRequest.SlotPlan> slots = new ArrayList<>(96);
        for (int s = 0; s < 96; s++) {
            int v1 = s == 0 ? s1 : 1000;
            int v2 = s == 0 ? s2 : 1000;
            int v3 = s == 0 ? s3 : 2000;
            slots.add(new PlanPublishRequest.SlotPlan(s, List.of(
                    new PlanPublishRequest.TurbinePlan("T-01", v1),
                    new PlanPublishRequest.TurbinePlan("T-02", v2),
                    new PlanPublishRequest.TurbinePlan("T-03", v3))));
        }
        return new PlanPublishRequest(requestId, FARM, DATE, expectedVersion, slots);
    }

    private PlanOrderReceiveRequest bindReq(String requestId, int targetKw, int version) {
        return new PlanOrderReceiveRequest(requestId, FARM, targetKw, DATE, 0, version);
    }

    private Map<String, PlanBindingEvidenceResponse.TurbineBaseView> basesByCode(
            PlanBindingEvidenceResponse evidence) {
        return evidence.bases().stream()
                .collect(Collectors.toMap(b -> b.turbineCode(), b -> b));
    }

    @Test
    @DisplayName("绑定指令主流程：可参与出力取 min(实时可用, 核定计划)，分摊合计恰等于目标并冻结基数")
    void boundOrderUsesMinOfAvailableAndPlan() {
        // 时段 0 核定：1500/1200/2000；实时可用 2000/1600/2400 → 可参与 1500/1200/2000=4700。
        planService.publish(planWithSlot0("plan-a", 0, 1500, 1200, 2000));

        OrderReceiveResponse resp = dispatchService.receivePlanBound(bindReq("bind-1", 4000, 1));

        assertThat(resp.status()).isEqualTo("DISPATCHED");
        assertThat(resp.farmAvailableKw()).isEqualTo(4700);
        assertThat(resp.curtailedKw()).isEqualTo(700);
        int sum = resp.allocations().stream().mapToInt(a -> a.targetPowerKw()).sum();
        assertThat(sum).isEqualTo(4000);
        // 分摊基数为可参与出力（min 取值），不是实时可用。
        Map<String, Integer> baseByCode = resp.allocations().stream()
                .collect(Collectors.toMap(a -> a.turbineCode(), a -> a.availablePowerKw()));
        assertThat(baseByCode.get("T-01")).isEqualTo(1500);
        assertThat(baseByCode.get("T-02")).isEqualTo(1200);
        assertThat(baseByCode.get("T-03")).isEqualTo(2000);
        assertThat(resp.allocations()).allSatisfy(a ->
                assertThat(a.targetPowerKw()).isGreaterThanOrEqualTo(
                        Math.min(a.availablePowerKw(), a.technicalMinKw())));

        // 基数证据：版本与机组基数已冻结。
        PlanBindingEvidenceResponse evidence =
                dispatchService.planBindingEvidence(resp.orderNo());
        assertThat(evidence.bound()).isTrue();
        assertThat(evidence.planVersion()).isEqualTo(1);
        assertThat(evidence.planDate()).isEqualTo(DATE);
        assertThat(evidence.slotIndex()).isEqualTo(0);
        assertThat(evidence.slotStartUtc())
                .isEqualTo(DATE.atStartOfDay(java.time.ZoneOffset.UTC).toInstant());
        assertThat(evidence.slotEndUtc())
                .isEqualTo(DATE.atTime(0, 15).toInstant(java.time.ZoneOffset.UTC));
        Map<String, PlanBindingEvidenceResponse.TurbineBaseView> bases = basesByCode(evidence);
        assertThat(bases.get("T-01").plannedKw()).isEqualTo(1500);
        assertThat(bases.get("T-01").availableKw()).isEqualTo(2000);
        assertThat(bases.get("T-01").effectiveKw()).isEqualTo(1500);
        assertThat(bases.get("T-02").plannedKw()).isEqualTo(1200);
        assertThat(bases.get("T-02").availableKw()).isEqualTo(1600);
        assertThat(bases.get("T-03").plannedKw()).isEqualTo(2000);
        assertThat(bases.get("T-03").availableKw()).isEqualTo(2400);
    }

    @Test
    @DisplayName("受理后发布新计划：已受理指令基数与版本不被重算，重复查询稳定")
    void newPlanPublishDoesNotRecomputeAcceptedOrder() {
        planService.publish(planWithSlot0("plan-b1", 0, 1500, 1200, 2000));
        OrderReceiveResponse order = dispatchService.receivePlanBound(bindReq("bind-2", 4000, 1));

        // 发布 v2（时段 0 改为全 500），当前指针切换到 v2。
        PlanPublishResponse v2 = planService.publish(planWithSlot0("plan-b2", 1, 500, 500, 500));
        assertThat(v2.version()).isEqualTo(2);
        assertThat(planService.current(FARM, DATE).version()).isEqualTo(2);

        // 已受理指令仍冻结 v1 基数。
        PlanBindingEvidenceResponse evidence =
                dispatchService.planBindingEvidence(order.orderNo());
        assertThat(evidence.planVersion()).isEqualTo(1);
        Map<String, PlanBindingEvidenceResponse.TurbineBaseView> bases = basesByCode(evidence);
        assertThat(bases.get("T-01").plannedKw()).isEqualTo(1500);
        assertThat(bases.get("T-02").plannedKw()).isEqualTo(1200);
        assertThat(bases.get("T-03").plannedKw()).isEqualTo(2000);

        // 重复查询稳定：两次证据一致；首轮分摊明细不变。
        PlanBindingEvidenceResponse again =
                dispatchService.planBindingEvidence(order.orderNo());
        assertThat(again).isEqualTo(evidence);
        OrderDetailResponse detail = dispatchService.detail(order.orderNo());
        assertThat(detail.cycles()).hasSize(1);
        int sum = detail.cycles().get(0).allocations().stream()
                .mapToInt(a -> a.targetPowerKw()).sum();
        assertThat(sum).isEqualTo(4000);
    }

    @Test
    @DisplayName("过期版本不允许被新指令绑定：422；不存在的版本：404")
    void staleOrMissingVersionRejected() {
        planService.publish(planWithSlot0("plan-c1", 0, 1500, 1200, 2000));
        planService.publish(planWithSlot0("plan-c2", 1, 900, 900, 1800));

        // v1 已过期 → 422。
        assertThatThrownBy(() -> dispatchService.receivePlanBound(bindReq("bind-stale", 3000, 1)))
                .isInstanceOf(ApiUnprocessableException.class);
        // 不存在的版本 → 404。
        assertThatThrownBy(() -> dispatchService.receivePlanBound(bindReq("bind-none", 3000, 9)))
                .isInstanceOf(ApiNotFoundException.class);
        // 当前版本 v2 可绑定。
        OrderReceiveResponse ok = dispatchService.receivePlanBound(bindReq("bind-cur", 3000, 2));
        assertThat(ok.status()).isEqualTo("DISPATCHED");
        assertThat(dispatchService.planBindingEvidence(ok.orderNo()).planVersion()).isEqualTo(2);
    }

    @Test
    @DisplayName("目标不可实现整单拒绝 422：高于可参与出力或低于可实现下限；失败不占幂等键")
    void infeasibleTargetRejectedAndKeyNotConsumed() {
        // 时段 0 核定 1500/1200/2000 → 可参与合计 4700。
        planService.publish(planWithSlot0("plan-d", 0, 1500, 1200, 2000));

        // 高于可参与出力 → 422。
        assertThatThrownBy(() -> dispatchService.receivePlanBound(bindReq("bind-high", 4701, 1)))
                .isInstanceOf(ApiUnprocessableException.class);
        // 失败不占键：同 requestId 改为可行目标后受理成功。
        OrderReceiveResponse ok = dispatchService.receivePlanBound(bindReq("bind-high", 4000, 1));
        assertThat(ok.status()).isEqualTo("DISPATCHED");

        // 核定极低（100/100/100）：可实现下限 = 300，目标 200 → 422。
        pinClock(11, 0, 0);
        planService.publish(planWithSlot0("plan-d2", 1, 100, 100, 100));
        assertThatThrownBy(() -> dispatchService.receivePlanBound(bindReq("bind-low", 200, 2)))
                .isInstanceOf(ApiUnprocessableException.class);
        // 目标恰为下限 300：合法受理。
        OrderReceiveResponse atFloor =
                dispatchService.receivePlanBound(bindReq("bind-floor", 300, 2));
        assertThat(atFloor.allocations().stream().mapToInt(a -> a.targetPowerKw()).sum())
                .isEqualTo(300);
    }

    @Test
    @DisplayName("绑定指令同键同参重放返回首次冻结结果；同键异参 409")
    void boundOrderIdempotentReplayAndConflict() {
        planService.publish(planWithSlot0("plan-e", 0, 1500, 1200, 2000));
        PlanOrderReceiveRequest req = bindReq("bind-idem", 4000, 1);

        OrderReceiveResponse r1 = dispatchService.receivePlanBound(req);
        OrderReceiveResponse r2 = dispatchService.receivePlanBound(req);
        assertThat(r2.replayed()).isTrue();
        assertThat(r2.orderId()).isEqualTo(r1.orderId());
        assertThat(r2.allocations()).isEqualTo(r1.allocations());

        // 同键改目标 → 409。
        assertThatThrownBy(() -> dispatchService.receivePlanBound(bindReq("bind-idem", 3999, 1)))
                .isInstanceOf(ApiConflictException.class);
        // 同键改时段 → 409。
        assertThatThrownBy(() -> dispatchService.receivePlanBound(
                new PlanOrderReceiveRequest("bind-idem", FARM, 4000, DATE, 1, 1)))
                .isInstanceOf(ApiConflictException.class);

        Integer orderCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM curtail_order", Integer.class);
        assertThat(orderCount).isEqualTo(1);
    }

    @Test
    @DisplayName("超时重分摊仍引用受理时冻结的原计划，仅实时可用值随回报更新")
    void timeoutReallocationUsesFrozenPlanWithFreshAvailable() {
        planService.publish(planWithSlot0("plan-f1", 0, 1500, 1200, 2000));
        OrderReceiveResponse order = dispatchService.receivePlanBound(bindReq("bind-f", 4000, 1));

        // 受理后发布 v2（时段 0 全 500），不影响已受理指令。
        planService.publish(planWithSlot0("plan-f2", 1, 500, 500, 500));

        // 恰满 10 分钟：t-01 实测 1450（实时可用刷新为 1450），全场 5450 > 4000 → 告警重分摊。
        pinClock(10, 10, 0);
        dispatchService.report(new ReportRequest("rp-f", "T-01",
                localInstant(10, 10, 0), 1450));

        OrderDetailResponse detail = dispatchService.detail(order.orderNo());
        assertThat(detail.alerts()).hasSize(1);
        assertThat(detail.currentCycleNo()).isEqualTo(2);
        var cycle2 = detail.cycles().get(1).allocations();
        int sum = cycle2.stream().mapToInt(a -> a.targetPowerKw()).sum();
        assertThat(sum).isEqualTo(4000);
        Map<String, Integer> baseByCode = cycle2.stream()
                .collect(Collectors.toMap(a -> a.turbineCode(), a -> a.availablePowerKw()));
        // t-01：min(最新实时 1450, 冻结计划 1500) = 1450（仅实时值更新）。
        assertThat(baseByCode.get("T-01")).isEqualTo(1450);
        // t-02：min(实时 1600, 冻结计划 1200) = 1200（仍引用 v1 冻结计划，而非 v2 的 500）。
        assertThat(baseByCode.get("T-02")).isEqualTo(1200);
        // t-03：min(实时 2400, 冻结计划 2000) = 2000。
        assertThat(baseByCode.get("T-03")).isEqualTo(2000);
    }

    @Test
    @DisplayName("未绑定计划的指令行为不变：基数证据 bound=false；未知指令证据查询 404")
    void unboundOrderEvidenceAndUnknownOrder() {
        OrderReceiveResponse order = dispatchService.receive(
                new OrderReceiveRequest("plain-1", FARM, 5000));
        assertThat(order.farmAvailableKw()).isEqualTo(6000);

        PlanBindingEvidenceResponse evidence =
                dispatchService.planBindingEvidence(order.orderNo());
        assertThat(evidence.bound()).isFalse();
        assertThat(evidence.bases()).isEmpty();

        assertThatThrownBy(() -> dispatchService.planBindingEvidence("no-such-order"))
                .isInstanceOf(ApiNotFoundException.class);
    }
}
