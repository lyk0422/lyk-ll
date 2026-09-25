package com.example.starter.irrigation;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.starter.irrigation.api.GroupDtos.CreateGroupRequest;
import com.example.starter.irrigation.api.GroupDtos.OutletMember;
import com.example.starter.irrigation.api.MeteringDtos.MeterReportRequest;
import com.example.starter.irrigation.api.PlanDtos.PlanVersionResponse;
import com.example.starter.irrigation.api.PlanDtos.PublishPlanRequest;
import com.example.starter.irrigation.api.SettlementDtos.SealRequest;
import com.example.starter.irrigation.api.SettlementDtos.SettlementResponse;
import com.example.starter.irrigation.service.GroupService;
import com.example.starter.irrigation.service.MeteringService;
import com.example.starter.irrigation.service.PlanService;
import com.example.starter.irrigation.service.SettlementService;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 欠供跨轮结转承接 H2 测试：上一轮封存结转量计入下一轮计划明细总目标，
 * 超出 30% 作废部分不结转。
 */
class CrossTurnCarryoverH2Test extends AbstractIntegrationTest {

    private static final String GROUP = "G-CROSS";
    private static final long TURN_T = 2960L;
    private static final long TURN_T1 = TURN_T + 1;

    @Autowired
    private GroupService groupService;
    @Autowired
    private PlanService planService;
    @Autowired
    private MeteringService meteringService;
    @Autowired
    private SettlementService settlementService;

    @Test
    void carryoverFromSealedTurnIsCarriedIntoNextTurnPlanTarget() {
        // 单斗口：T 轮计划 100，实供 70，欠 30 恰满 30% 上限，全额结转
        groupService.createGroup(new CreateGroupRequest(GROUP, "跨轮组", List.of(
                new OutletMember("D001", "界斗口", 1L))));
        planService.publish(new PublishPlanRequest("req-t", GROUP, TURN_T, 100L));
        meteringService.report(new MeterReportRequest("m-t", GROUP, "D001", TURN_T, 70L));
        SettlementResponse sealedT = settlementService.seal(new SealRequest(GROUP, TURN_T));
        assertThat(sealedT.lines().get(0).carryoverCubicMeter()).isEqualTo(30L);

        // T+1 轮首次发布（新轮次无生效计划）：明细自动承接上轮结转 30
        PlanVersionResponse planT1 = planService.publish(
                new PublishPlanRequest("req-t1", GROUP, TURN_T1, 100L));
        assertThat(planT1.lines()).singleElement().satisfies(line -> {
            assertThat(line.planCubicMeter()).isEqualTo(100L);
            assertThat(line.carryoverCubicMeter()).isEqualTo(30L);
        });

        // 同键重放返回首次结果（指纹已含结转量）
        PlanVersionResponse replay = planService.publish(
                new PublishPlanRequest("req-t1", GROUP, TURN_T1, 100L));
        assertThat(replay.planId()).isEqualTo(planT1.planId());

        // T+1 实供 130 = 权重计划 100 + 上轮结转 30，总目标恰好满足，无欠供
        meteringService.report(new MeterReportRequest("m-t1", GROUP, "D001", TURN_T1, 130L));
        SettlementResponse sealedT1 = settlementService.seal(new SealRequest(GROUP, TURN_T1));
        assertThat(sealedT1.lines().get(0).shortageCubicMeter()).isZero();
        assertThat(sealedT1.lines().get(0).carryoverCubicMeter()).isZero();
        // 30% 上限基数仍是本轮按权重计划量 100
        assertThat(sealedT1.lines().get(0).planCubicMeter()).isEqualTo(100L);
    }

    @Test
    void shortageAboveCapIsVoidedAndOnlyCappedAmountCarriesToNextTurn() {
        // 三斗口等面积，T 轮总量 10：D001=4, D002=3, D003=3；全部零实供
        groupService.createGroup(new CreateGroupRequest("G-VOID", "作废组", List.of(
                new OutletMember("D001", "东斗口", 1L),
                new OutletMember("D002", "西斗口", 1L),
                new OutletMember("D003", "北斗口", 1L))));
        planService.publish(new PublishPlanRequest("req-vt", "G-VOID", TURN_T, 10L));
        SettlementResponse sealedT = settlementService.seal(new SealRequest("G-VOID", TURN_T));
        // D001 欠 4，上限 floor(4*0.3)=1，结转 1 作废 3；D002/D003 欠 3，上限 0，全额作废
        assertThat(sealedT.lines()).extracting(l -> l.outletCode()
                        + ":carry=" + l.carryoverCubicMeter() + ",void=" + l.voidedCubicMeter())
                .containsExactly("D001:carry=1,void=3",
                        "D002:carry=0,void=3",
                        "D003:carry=0,void=3");

        // T+1 计划只有 D001 承接 1 m3 结转
        PlanVersionResponse planT1 = planService.publish(
                new PublishPlanRequest("req-vt1", "G-VOID", TURN_T1, 10L));
        assertThat(planT1.lines()).extracting(l -> l.outletCode()
                        + ":plan=" + l.planCubicMeter() + ",carry=" + l.carryoverCubicMeter())
                .containsExactly("D001:plan=4,carry=1",
                        "D002:plan=3,carry=0",
                        "D003:plan=3,carry=0");

        // T+1 仍全部零实供：D001 总目标 5，欠 5，上限 floor(4*0.3)=1，结转 1 作废 4
        SettlementResponse sealedT1 = settlementService.seal(new SealRequest("G-VOID", TURN_T1));
        assertThat(sealedT1.lines()).extracting(l -> l.outletCode()
                        + ":short=" + l.shortageCubicMeter()
                        + ",carry=" + l.carryoverCubicMeter()
                        + ",void=" + l.voidedCubicMeter())
                .containsExactly("D001:short=5,carry=1,void=4",
                        "D002:short=3,carry=0,void=3",
                        "D003:short=3,carry=0,void=3");
    }

    @Test
    void firstTurnPlanHasNoCarryoverWithoutPriorSettlement() {
        groupService.createGroup(new CreateGroupRequest("G-FIRST", "首轮组", List.of(
                new OutletMember("D001", "东斗口", 2L),
                new OutletMember("D002", "西斗口", 1L))));
        // 上一轮未封存：无结转承接
        PlanVersionResponse plan = planService.publish(
                new PublishPlanRequest("req-first-turn", "G-FIRST", 5000L, 9L));
        assertThat(plan.lines()).extracting(l -> l.carryoverCubicMeter())
                .containsExactly(0L, 0L);
    }
}
