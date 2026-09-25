package com.example.starter.irrigation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.starter.irrigation.api.GroupDtos.CreateGroupRequest;
import com.example.starter.irrigation.api.GroupDtos.OutletMember;
import com.example.starter.irrigation.api.MeteringDtos.MeterReportRequest;
import com.example.starter.irrigation.api.PlanDtos.PublishPlanRequest;
import com.example.starter.irrigation.api.PurchaseDtos.ExtraPurchaseRequest;
import com.example.starter.irrigation.api.PurchaseDtos.ExtraPurchaseResponse;
import com.example.starter.irrigation.api.SettlementDtos.SealRequest;
import com.example.starter.irrigation.service.BusinessRuleException;
import com.example.starter.irrigation.service.ConflictException;
import com.example.starter.irrigation.service.GroupService;
import com.example.starter.irrigation.service.MeteringService;
import com.example.starter.irrigation.service.PlanService;
import com.example.starter.irrigation.service.PurchaseService;
import com.example.starter.irrigation.service.SettlementService;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 临时加购水量 H2 事务测试：优先欠供、按欠供比例取整、补供落账、幂等与封存终态。
 */
class PurchaseH2Test extends AbstractIntegrationTest {

    private static final String GROUP = "G-PURCHASE";
    private static final long TURN = 2960L;

    @Autowired
    private GroupService groupService;
    @Autowired
    private PlanService planService;
    @Autowired
    private MeteringService meteringService;
    @Autowired
    private PurchaseService purchaseService;
    @Autowired
    private SettlementService settlementService;

    private void seedGroupAndPlan() {
        // 总面积 10，总量 100：D001 计划 70、D002 计划 20、D003 计划 10
        groupService.createGroup(new CreateGroupRequest(GROUP, "加购测试组", List.of(
                new OutletMember("D001", "东斗口", 7L),
                new OutletMember("D002", "西斗口", 2L),
                new OutletMember("D003", "北斗口", 1L))));
        planService.publish(new PublishPlanRequest("req-plan", GROUP, TURN, 100L));
    }

    @Test
    void extraPurchasePrioritizesShortageOutletsAndAllocatesByShortageRatio() {
        seedGroupAndPlan();
        // D001 实供 0（欠 70），D002 实供 20（不欠），D003 从未上报（欠 10）
        meteringService.report(new MeterReportRequest("e1", GROUP, "D001", TURN, 0L));
        meteringService.report(new MeterReportRequest("e2", GROUP, "D002", TURN, 20L));

        // 欠供总额 80，加购 8：D001=floor(8*70/80)=7，D003=floor(8*10/80)=1
        ExtraPurchaseResponse response = purchaseService.purchase(
                new ExtraPurchaseRequest("pur-1", GROUP, TURN, 8L));

        assertThat(response.allocatedCubicMeter()).isEqualTo(8L);
        // 不欠供的 D002 不获配
        assertThat(response.lines()).extracting(l -> l.outletCode() + "=" + l.allocatedCubicMeter())
                .containsExactly("D001=7", "D003=1");
        assertThat(response.lines()).extracting(l -> l.outletCode() + "/" + l.shortageCubicMeter())
                .containsExactly("D001/70", "D003/10");

        // 补供视同已确认实供落账：D001 由 0 变 7，D003 建立快照为 1
        assertThat(meteringService.report(
                new MeterReportRequest("check-1", GROUP, "D001", TURN, 7L)).deliveredCubicMeter())
                .isEqualTo(7L);
        Long d003 = jdbc.queryForObject(
                "SELECT delivered_cubic_meter FROM irr_metering WHERE turn_no = ? AND outlet_code = 'D003'",
                Long.class, TURN);
        assertThat(d003).isEqualTo(1L);
    }

    @Test
    void extraPurchaseAboveTotalShortageFullyCoversAndLeavesRest() {
        seedGroupAndPlan();
        meteringService.report(new MeterReportRequest("e1", GROUP, "D001", TURN, 67L)); // 欠 3
        // D002 欠 20、D003 欠 10，欠供总额 33；加购 100 只分配 33
        ExtraPurchaseResponse response = purchaseService.purchase(
                new ExtraPurchaseRequest("pur-big", GROUP, TURN, 100L));

        assertThat(response.allocatedCubicMeter()).isEqualTo(33L);
        assertThat(response.lines()).extracting(l -> l.outletCode() + "=" + l.allocatedCubicMeter())
                .containsExactly("D001=3", "D002=20", "D003=10");
    }

    @Test
    void secondPurchaseSeesReducedShortageAfterFirstAllocation() {
        seedGroupAndPlan();
        // 全部未供：欠 70/20/10。加购 8：floor 5/1/0，余 2 按最大余额补给 D003(余额80)、
        // D001(与 D002 并列 60，编号小者优先)，结果 6/1/1
        ExtraPurchaseResponse first = purchaseService.purchase(
                new ExtraPurchaseRequest("pur-a", GROUP, TURN, 8L));
        assertThat(first.lines()).extracting(l -> l.outletCode() + "=" + l.allocatedCubicMeter())
                .containsExactly("D001=6", "D002=1", "D003=1");

        // 欠供变为 64/19/9（总额 92），再加购 10：floor 6/2/0，余 2 按余额
        // D003=90、D001=88、D002=6 补给 D003、D001，结果 7/2/1
        ExtraPurchaseResponse second = purchaseService.purchase(
                new ExtraPurchaseRequest("pur-b", GROUP, TURN, 10L));
        assertThat(second.lines()).extracting(l -> l.outletCode() + "=" + l.allocatedCubicMeter())
                .containsExactly("D001=7", "D002=2", "D003=1");
        assertThat(second.allocatedCubicMeter()).isEqualTo(10L);
    }

    @Test
    void purchaseWithNoShortageIsRejected() {
        seedGroupAndPlan();
        meteringService.report(new MeterReportRequest("e1", GROUP, "D001", TURN, 70L));
        meteringService.report(new MeterReportRequest("e2", GROUP, "D002", TURN, 20L));
        meteringService.report(new MeterReportRequest("e3", GROUP, "D003", TURN, 10L));

        assertThatThrownBy(() -> purchaseService.purchase(
                new ExtraPurchaseRequest("pur-none", GROUP, TURN, 5L)))
                .isInstanceOf(BusinessRuleException.class);
    }

    @Test
    void samePurchaseRequestIdReplaysFirstResult() {
        seedGroupAndPlan();
        ExtraPurchaseResponse first = purchaseService.purchase(
                new ExtraPurchaseRequest("pur-replay", GROUP, TURN, 8L));
        ExtraPurchaseResponse replay = purchaseService.purchase(
                new ExtraPurchaseRequest("pur-replay", GROUP, TURN, 8L));

        assertThat(replay.purchaseId()).isEqualTo(first.purchaseId());
        assertThat(replay.lines()).usingRecursiveComparison().isEqualTo(first.lines());
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(1) FROM irr_extra_purchase WHERE request_id = 'pur-replay'",
                Integer.class);
        assertThat(count).isEqualTo(1);
    }

    @Test
    void samePurchaseRequestIdDifferentAmountReturns409() {
        seedGroupAndPlan();
        purchaseService.purchase(new ExtraPurchaseRequest("pur-conflict", GROUP, TURN, 8L));
        assertThatThrownBy(() -> purchaseService.purchase(
                new ExtraPurchaseRequest("pur-conflict", GROUP, TURN, 9L)))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void purchaseAfterSealIsRejected() {
        seedGroupAndPlan();
        settlementService.seal(new SealRequest(GROUP, TURN));
        assertThatThrownBy(() -> purchaseService.purchase(
                new ExtraPurchaseRequest("pur-late", GROUP, TURN, 8L)))
                .isInstanceOf(BusinessRuleException.class);
    }
}
