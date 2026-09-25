package com.example.starter.irrigation;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.starter.irrigation.api.GroupDtos.CreateGroupRequest;
import com.example.starter.irrigation.api.GroupDtos.OutletMember;
import com.example.starter.irrigation.api.MeteringDtos.MeterReportRequest;
import com.example.starter.irrigation.api.MeteringDtos.MeterSnapshotResponse;
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
 * 计量去重与封存终态 H2 事务测试。
 */
class MeteringAndSettlementH2Test extends AbstractIntegrationTest {

    private static final String GROUP = "G-METER";
    private static final long TURN = 2960L;

    @Autowired
    private GroupService groupService;
    @Autowired
    private PlanService planService;
    @Autowired
    private MeteringService meteringService;
    @Autowired
    private SettlementService settlementService;

    private void seedGroupAndPlan(long available) {
        groupService.createGroup(new CreateGroupRequest(GROUP, "计量测试组", List.of(
                new OutletMember("D001", "东斗口", 1L),
                new OutletMember("D002", "西斗口", 1L),
                new OutletMember("D003", "北斗口", 1L))));
        // 等面积，总量 available：权重各 0.3333，差额归编号最小 D001
        planService.publish(new PublishPlanRequest("req-plan", GROUP, TURN, available));
    }

    @Test
    void duplicateEventIdIsNotAccumulatedTwice() {
        seedGroupAndPlan(10L); // D001=4, D002=3, D003=3
        MeterSnapshotResponse first = meteringService.report(
                new MeterReportRequest("evt-1", GROUP, "D001", TURN, 4L));
        MeterSnapshotResponse duplicate = meteringService.report(
                new MeterReportRequest("evt-1", GROUP, "D001", TURN, 999L));

        assertThat(first.duplicate()).isFalse();
        assertThat(duplicate.duplicate()).isTrue();
        assertThat(duplicate.deliveredCubicMeter()).isEqualTo(4L);
        assertThat(duplicate.eventCount()).isEqualTo(1);

        Long delivered = jdbc.queryForObject(
                "SELECT delivered_cubic_meter FROM irr_metering WHERE turn_no = ? AND outlet_code = 'D001'",
                Long.class, TURN);
        assertThat(delivered).isEqualTo(4L);
    }

    @Test
    void distinctEventsOnSameOutletAndTurnKeepLatestConfirmedReading() {
        seedGroupAndPlan(10L);
        meteringService.report(new MeterReportRequest("evt-a", GROUP, "D001", TURN, 1L));
        MeterSnapshotResponse latest = meteringService.report(
                new MeterReportRequest("evt-b", GROUP, "D001", TURN, 3L));

        // 读数语义为本轮累计读数：后到确认覆盖旧读数，不是重复累计
        assertThat(latest.deliveredCubicMeter()).isEqualTo(3L);
        assertThat(latest.eventCount()).isEqualTo(2);
    }

    @Test
    void settlementUsesLastConfirmedSnapshotAndAppliesThirtyPercentCap() {
        seedGroupAndPlan(10L); // D001=4, D002=3, D003=3
        meteringService.report(new MeterReportRequest("e1", GROUP, "D001", TURN, 1L)); // 欠 3
        meteringService.report(new MeterReportRequest("e2", GROUP, "D002", TURN, 3L)); // 欠 0
        // D003 不上报，实供按 0：欠 3；30% 上限=floor(3*0.3)=0，全额作废
        // D001 欠 3，上限 floor(4*0.3)=1，结转 1，作废 2
        SettlementResponse settlement = settlementService.seal(new SealRequest(GROUP, TURN));

        assertThat(settlement.status()).isEqualTo("SEALED");
        assertThat(settlement.idempotentReplay()).isFalse();
        assertThat(settlement.lines()).hasSize(3);
        assertThat(settlement.lines()).extracting(l -> l.outletCode()
                        + ":plan=" + l.planCubicMeter()
                        + ",del=" + l.deliveredCubicMeter()
                        + ",short=" + l.shortageCubicMeter()
                        + ",carry=" + l.carryoverCubicMeter()
                        + ",void=" + l.voidedCubicMeter())
                .containsExactly(
                        "D001:plan=4,del=1,short=3,carry=1,void=2",
                        "D002:plan=3,del=3,short=0,carry=0,void=0",
                        "D003:plan=3,del=0,short=3,carry=0,void=3");
    }

    @Test
    void exactThirtyPercentShortageIsFullyCarried() {
        // 单斗口组，计划 100，欠供恰 30：允许全额结转
        groupService.createGroup(new CreateGroupRequest("G-CAP", "边界组", List.of(
                new OutletMember("D100", "界斗口", 1L))));
        planService.publish(new PublishPlanRequest("req-cap", "G-CAP", TURN, 100L));
        meteringService.report(new MeterReportRequest("e-cap", "G-CAP", "D100", TURN, 70L));

        SettlementResponse settlement = settlementService.seal(new SealRequest("G-CAP", TURN));
        assertThat(settlement.lines()).singleElement()
                .satisfies(line -> {
                    assertThat(line.shortageCubicMeter()).isEqualTo(30L);
                    assertThat(line.carryoverCubicMeter()).isEqualTo(30L);
                    assertThat(line.voidedCubicMeter()).isZero();
                });
    }

    @Test
    void repeatedSealIsIdempotentAndReturnsFirstResult() {
        seedGroupAndPlan(10L);
        meteringService.report(new MeterReportRequest("e1", GROUP, "D001", TURN, 4L));
        SettlementResponse first = settlementService.seal(new SealRequest(GROUP, TURN));
        SettlementResponse replay = settlementService.seal(new SealRequest(GROUP, TURN));

        assertThat(replay.idempotentReplay()).isTrue();
        assertThat(replay.sealedAt()).isEqualTo(first.sealedAt());
        assertThat(replay.lines()).usingRecursiveComparison().isEqualTo(first.lines());

        Integer settlementCount = jdbc.queryForObject(
                "SELECT COUNT(1) FROM irr_settlement WHERE turn_no = ? AND group_code = ?",
                Integer.class, TURN, GROUP);
        assertThat(settlementCount).isEqualTo(1);
    }

    @Test
    void lateMeteringAfterSealIsRecordedButDoesNotChangeCarryover() {
        seedGroupAndPlan(10L);
        meteringService.report(new MeterReportRequest("e-before", GROUP, "D001", TURN, 0L));
        settlementService.seal(new SealRequest(GROUP, TURN));

        MeterSnapshotResponse late = meteringService.report(
                new MeterReportRequest("e-late", GROUP, "D001", TURN, 4L));
        assertThat(late.late()).isTrue();
        // 计量快照不被迟到读数改写
        assertThat(late.deliveredCubicMeter()).isZero();

        // 结转结果不变
        SettlementResponse after = settlementService.seal(new SealRequest(GROUP, TURN));
        assertThat(after.lines().get(0).deliveredCubicMeter()).isZero();
        assertThat(after.lines().get(0).shortageCubicMeter()).isEqualTo(4L);

        Integer lateCount = jdbc.queryForObject(
                "SELECT COUNT(1) FROM irr_late_meter_event WHERE event_id = 'e-late'", Integer.class);
        assertThat(lateCount).isEqualTo(1);

        // 迟到事件重放也幂等
        MeterSnapshotResponse lateReplay = meteringService.report(
                new MeterReportRequest("e-late", GROUP, "D001", TURN, 4L));
        assertThat(lateReplay.late()).isTrue();
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(1) FROM irr_late_meter_event WHERE event_id = 'e-late'", Integer.class))
                .isEqualTo(1);
    }
}
