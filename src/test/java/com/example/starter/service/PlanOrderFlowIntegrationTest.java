package com.example.starter.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import com.example.starter.AbstractIntegrationTest;
import com.example.starter.dto.OrderBasisResponse;
import com.example.starter.dto.PlanOrderReceiveRequest;
import com.example.starter.dto.PlanOrderReceiveResponse;
import com.example.starter.dto.PlanPublishRequest;
import com.example.starter.dto.ReportRequest;
import com.example.starter.web.ApiConflictException;
import com.example.starter.web.ApiNotFoundException;
import com.example.starter.web.ApiUnprocessableException;

/**
 * H2 事务集成测试：计划绑定指令受理（可参与出力=min(实时,核定计划)）、
 * 基数冻结与证据查询、新计划发布不重算已受理指令、过期版本拒绝、超时重分摊引用原计划、
 * 幂等重放/异参 409/失败不占键。
 */
@SpringBootTest
class PlanOrderFlowIntegrationTest extends AbstractIntegrationTest {

    /** 绑定时段：第 40 段 = 10:00-10:15 UTC。 */
    private static final int SEGMENT = 40;

    @Autowired
    private PlanService planService;

    @Autowired
    private DispatchService dispatchService;

    @BeforeEach
    void setUp() {
        resetDb();
        pinClock(10, 0, 0);
    }

    private void publishV1() {
        // T-01 核定 1500（低于实时 2000），T-02 1600（恰等于实时），T-03 2000（低于实时 2400）。
        List<com.example.starter.dto.PlanSegmentInput> segments = fullSegments((idx, code) -> switch (code) {
            case "T-01" -> 1500;
            case "T-02" -> 1600;
            default -> 2000;
        });
        planService.publish(new PlanPublishRequest("plan-v1", FARM, PLAN_DATE, 1,
                localInstant(9, 0, 0), segments));
    }

    private PlanOrderReceiveRequest orderReq(String requestId, int target) {
        return new PlanOrderReceiveRequest(requestId, FARM, target, PLAN_DATE,
                SEGMENT, 1, localInstant(10, 0, 0));
    }

    @Test
    @DisplayName("受理主流程：可参与出力=min(实时可用,核定计划)=1500/1600/2000=5100，目标合计精确")
    void receivePlanOrderUsesMinBasis() {
        publishV1();
        PlanOrderReceiveResponse resp = dispatchService.receivePlanOrder(orderReq("po-1", 4000));

        assertThat(resp.planVersionNo()).isEqualTo(1);
        assertThat(resp.versionCurrent()).isTrue();
        assertThat(resp.farmParticipatingKw()).isEqualTo(5100);
        assertThat(resp.curtailedKw()).isEqualTo(1100);
        assertThat(resp.status()).isEqualTo("DISPATCHED");
        assertThat(resp.replayed()).isFalse();

        var byCode = resp.allocations().stream()
                .collect(java.util.stream.Collectors.toMap(
                        com.example.starter.dto.AllocationItem::turbineCode, a -> a));
        assertThat(byCode.get("T-01").availablePowerKw()).isEqualTo(1500);
        assertThat(byCode.get("T-01").plannedPowerKw()).isEqualTo(1500);
        assertThat(byCode.get("T-01").realtimeAvailableKw()).isEqualTo(2000);
        assertThat(byCode.get("T-02").availablePowerKw()).isEqualTo(1600);
        assertThat(byCode.get("T-03").availablePowerKw()).isEqualTo(2000);
        assertThat(byCode.get("T-03").realtimeAvailableKw()).isEqualTo(2400);
        int sum = resp.allocations().stream()
                .mapToInt(com.example.starter.dto.AllocationItem::targetPowerKw).sum();
        assertThat(sum).isEqualTo(4000);
        assertThat(resp.allocations()).allSatisfy(a ->
                assertThat(a.targetPowerKw()).isGreaterThanOrEqualTo(a.technicalMinKw()));
    }

    @Test
    @DisplayName("基数证据：版本、UTC 半开时段与各机组计划/实时/参与出力冻结值可复算")
    void basisEvidenceFrozen() {
        publishV1();
        PlanOrderReceiveResponse order = dispatchService.receivePlanOrder(orderReq("po-2", 4000));

        OrderBasisResponse basis = dispatchService.basis(order.orderNo());
        assertThat(basis.planVersionNo()).isEqualTo(1);
        assertThat(basis.versionCurrent()).isTrue();
        assertThat(basis.segmentIndex()).isEqualTo(SEGMENT);
        assertThat(basis.segmentStart().toString()).isEqualTo("2026-09-27T10:00:00Z");
        assertThat(basis.segmentEnd().toString()).isEqualTo("2026-09-27T10:15:00Z");
        assertThat(basis.cycles()).hasSize(1);

        var byCode = basis.cycles().get(0).items().stream()
                .collect(java.util.stream.Collectors.toMap(
                        OrderBasisResponse.TurbineBasisItem::turbineCode, i -> i));
        OrderBasisResponse.TurbineBasisItem t1 = byCode.get("T-01");
        assertThat(t1.plannedPowerKw()).isEqualTo(1500);
        assertThat(t1.realtimeAvailableKw()).isEqualTo(2000);
        assertThat(t1.participatingPowerKw()).isEqualTo(1500);
        assertThat(t1.technicalMinKw()).isEqualTo(300);
        int targetSum = basis.cycles().get(0).items().stream()
                .mapToInt(OrderBasisResponse.TurbineBasisItem::targetPowerKw).sum();
        assertThat(targetSum).isEqualTo(4000);
    }

    @Test
    @DisplayName("新计划发布不重算已受理指令：历史基数不变，证据版本 current=false")
    void newPlanPublishDoesNotRecomputeAcceptedOrder() {
        publishV1();
        PlanOrderReceiveResponse order = dispatchService.receivePlanOrder(orderReq("po-3", 4000));

        // 发布 v2：核定计划显著下调（整版 800）。
        pinClock(10, 5, 0);
        planService.publish(new PlanPublishRequest("plan-v2", FARM, PLAN_DATE, 2,
                localInstant(10, 5, 0), fullSegments(800)));

        // 已受理指令仅一轮，首轮分摊与冻结基数未被重算。
        OrderBasisResponse basis = dispatchService.basis(order.orderNo());
        assertThat(basis.versionCurrent()).isFalse();
        assertThat(basis.cycles()).hasSize(1);
        assertThat(basis.cycles().get(0).items()).extracting(
                OrderBasisResponse.TurbineBasisItem::plannedPowerKw)
                .containsExactly(1500, 1600, 2000);
        int targetSum = basis.cycles().get(0).items().stream()
                .mapToInt(OrderBasisResponse.TurbineBasisItem::targetPowerKw).sum();
        assertThat(targetSum).isEqualTo(4000);
        assertThat(planService.getCurrentVersion(FARM, PLAN_DATE).versionNo()).isEqualTo(2);
        // 指令仍为执行中且未受影响。
        assertThat(dispatchService.detail(order.orderNo()).currentCycleNo()).isEqualTo(1);
    }

    @Test
    @DisplayName("过期版本不允许新指令绑定：422；版本不存在：404")
    void expiredVersionRejectedAndMissingVersion404() {
        publishV1();
        pinClock(10, 5, 0);
        planService.publish(new PlanPublishRequest("plan-v2b", FARM, PLAN_DATE, 2,
                localInstant(10, 5, 0), fullSegments(900)));

        PlanOrderReceiveRequest bindOld =
                new PlanOrderReceiveRequest("po-old", FARM, 4000, PLAN_DATE, SEGMENT, 1,
                        localInstant(10, 6, 0));
        assertThatThrownBy(() -> dispatchService.receivePlanOrder(bindOld))
                .isInstanceOf(ApiUnprocessableException.class)
                .hasMessageContaining("过期");

        PlanOrderReceiveRequest bindMissing =
                new PlanOrderReceiveRequest("po-miss", FARM, 4000, PLAN_DATE, SEGMENT, 9,
                        localInstant(10, 6, 0));
        assertThatThrownBy(() -> dispatchService.receivePlanOrder(bindMissing))
                .isInstanceOf(ApiNotFoundException.class);
    }

    @Test
    @DisplayName("目标不可实现：高于可参与出力或低于可实现下限均 422 整单拒绝且失败不占键")
    void infeasibleTargetRejected422AndKeyNotOccupied() {
        // 计划极低：参与出力 200/1600/2000=3800，可实现下限 200/300/450=950。
        var lowPlan = fullSegments((idx, code) -> switch (code) {
            case "T-01" -> 200;
            case "T-02" -> 1600;
            default -> 2000;
        });
        planService.publish(new PlanPublishRequest("plan-low", FARM, PLAN_DATE, 1,
                localInstant(9, 0, 0), lowPlan));

        // 高于可参与出力 3800：422。
        assertThatThrownBy(() -> dispatchService.receivePlanOrder(orderReq("po-bad", 3801)))
                .isInstanceOf(ApiUnprocessableException.class)
                .hasMessageContaining("高于全场可用出力");
        // 低于可实现下限 950：422。
        assertThatThrownBy(() -> dispatchService.receivePlanOrder(orderReq("po-bad2", 900)))
                .isInstanceOf(ApiUnprocessableException.class)
                .hasMessageContaining("下限");

        // 失败不占键：无指令、无幂等占位；同键改合法目标后受理成功。
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM curtail_order", Integer.class)).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM idempotent_record WHERE request_id = 'po-bad'",
                Integer.class)).isZero();
        PlanOrderReceiveResponse ok = dispatchService.receivePlanOrder(orderReq("po-bad", 2000));
        assertThat(ok.replayed()).isFalse();
        assertThat(ok.farmParticipatingKw()).isEqualTo(3800);
    }

    @Test
    @DisplayName("同键同参重放首次冻结结果；异目标/异时刻 409")
    void planOrderIdempotentReplayAndConflict() {
        publishV1();
        PlanOrderReceiveRequest req = orderReq("po-idem", 4000);
        PlanOrderReceiveResponse first = dispatchService.receivePlanOrder(req);
        PlanOrderReceiveResponse replay = dispatchService.receivePlanOrder(req);

        assertThat(replay.replayed()).isTrue();
        assertThat(replay.orderId()).isEqualTo(first.orderId());
        assertThat(replay.allocations()).isEqualTo(first.allocations());

        assertThatThrownBy(() ->
                dispatchService.receivePlanOrder(orderReq("po-idem", 3999)))
                .isInstanceOf(ApiConflictException.class);

        PlanOrderReceiveRequest differentTime = new PlanOrderReceiveRequest(
                "po-idem", FARM, 4000, PLAN_DATE, SEGMENT, 1, localInstant(10, 0, 1));
        assertThatThrownBy(() -> dispatchService.receivePlanOrder(differentTime))
                .isInstanceOf(ApiConflictException.class);

        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM curtail_order", Integer.class)).isEqualTo(1);
    }

    @Test
    @DisplayName("超时重分摊仍引用原计划：核定计划不变，仅实时可用刷新为回报值")
    void timeoutReallocationKeepsOriginalPlan() {
        publishV1();
        PlanOrderReceiveResponse order = dispatchService.receivePlanOrder(orderReq("po-timeout", 4000));

        // 判定时刻前（10:09）先把三机组实时可用刷新到 2000/1500/2300，此时不触发告警。
        pinClock(10, 9, 0);
        dispatchService.report(new ReportRequest("rp-1", "T-01", localInstant(10, 9, 0), 2000));
        dispatchService.report(new ReportRequest("rp-2", "T-02", localInstant(10, 9, 1), 1500));
        dispatchService.report(new ReportRequest("rp-3", "T-03", localInstant(10, 9, 2), 2300));
        assertThat(dispatchService.detail(order.orderNo()).currentCycleNo()).isEqualTo(1);

        // 恰满 10 分钟：参与出力=min(实时,计划)=1500/1500/2000=5000，仍高于目标 4000 → 重分摊第 2 轮。
        pinClock(10, 10, 0);
        dispatchService.report(new ReportRequest("rp-4", "T-01", localInstant(10, 10, 0), 2000));

        OrderBasisResponse basis = dispatchService.basis(order.orderNo());
        assertThat(basis.cycles()).hasSize(2);

        var cycle2 = basis.cycles().get(1).items().stream()
                .collect(java.util.stream.Collectors.toMap(
                        OrderBasisResponse.TurbineBasisItem::turbineCode, i -> i));
        // 核定计划始终引用受理时冻结的 v1 基数。
        assertThat(cycle2.get("T-01").plannedPowerKw()).isEqualTo(1500);
        assertThat(cycle2.get("T-02").plannedPowerKw()).isEqualTo(1600);
        assertThat(cycle2.get("T-03").plannedPowerKw()).isEqualTo(2000);
        // 实时值刷新；参与出力=min(实时,计划)：1500/1500/2000=5000，仍高于目标 4000 → 触发重分摊。
        assertThat(cycle2.get("T-01").realtimeAvailableKw()).isEqualTo(2000);
        assertThat(cycle2.get("T-02").realtimeAvailableKw()).isEqualTo(1500);
        assertThat(cycle2.get("T-03").realtimeAvailableKw()).isEqualTo(2300);
        assertThat(cycle2.get("T-01").participatingPowerKw()).isEqualTo(1500);
        assertThat(cycle2.get("T-02").participatingPowerKw()).isEqualTo(1500);
        assertThat(cycle2.get("T-03").participatingPowerKw()).isEqualTo(2000);
        int cycle2TargetSum = basis.cycles().get(1).items().stream()
                .mapToInt(OrderBasisResponse.TurbineBasisItem::targetPowerKw).sum();
        assertThat(cycle2TargetSum).isEqualTo(4000);
    }

    @Test
    @DisplayName("未绑定计划的旧指令无基数证据：404；未知指令：404")
    void basisAbsentForLegacyAndMissingOrder() {
        var legacy = dispatchService.receive(
                new com.example.starter.dto.OrderReceiveRequest("legacy-1", FARM, 5000));
        assertThatThrownBy(() -> dispatchService.basis(legacy.orderNo()))
                .isInstanceOf(ApiNotFoundException.class);
        assertThatThrownBy(() -> dispatchService.basis("NO-SUCH-ORDER"))
                .isInstanceOf(ApiNotFoundException.class);
    }
}
