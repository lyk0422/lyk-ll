package com.example.starter.irrigation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.starter.irrigation.api.GroupDtos.CreateGroupRequest;
import com.example.starter.irrigation.api.GroupDtos.OutletMember;
import com.example.starter.irrigation.api.PlanDtos.PlanVersionResponse;
import com.example.starter.irrigation.api.PlanDtos.PublishPlanRequest;
import com.example.starter.irrigation.service.ConflictException;
import com.example.starter.irrigation.service.GroupService;
import com.example.starter.irrigation.service.NotFoundException;
import com.example.starter.irrigation.service.PlanService;
import com.example.starter.irrigation.support.TurnClock;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 配水计划发布的 H2 事务测试：权重取整差额归属、幂等重放、异参 409、版本冻结与旧版可查。
 */
class PlanPublishH2Test extends AbstractIntegrationTest {

    private static final String GROUP = "G-PLAN";
    private static final long TURN = 2960L;

    @Autowired
    private GroupService groupService;
    @Autowired
    private PlanService planService;
    @Autowired
    private TurnClock turnClock;

    private void seedGroup() {
        groupService.createGroup(new CreateGroupRequest(GROUP, "计划测试组", List.of(
                new OutletMember("D001", "东斗口", 2L),
                new OutletMember("D002", "西斗口", 1L),
                new OutletMember("D003", "北斗口", 1L))));
    }

    @Test
    void publishAllocatesByAreaWeightsAndRemainderGoesToLargestOutlet() {
        seedGroup();
        // 总面积 4，总量 10 m3：权重 0.5/0.25/0.25 => 5+2+2=9，差额 1 归面积最大的 D001
        PlanVersionResponse plan = planService.publish(
                new PublishPlanRequest("req-p1", GROUP, TURN, 10L));

        assertThat(plan.versionNo()).isEqualTo(1);
        assertThat(plan.status()).isEqualTo("EFFECTIVE");
        assertThat(plan.lines()).hasSize(3);
        assertThat(plan.lines()).extracting(l -> l.outletCode() + "=" + l.planCubicMeter())
                .containsExactly("D001=6", "D002=2", "D003=2");
        assertThat(plan.lines()).extracting(l -> l.weight().toPlainString())
                .containsExactly("0.5000", "0.2500", "0.2500");
        long sum = plan.lines().stream().mapToLong(l -> l.planCubicMeter()).sum();
        assertThat(sum).isEqualTo(10L);
    }

    @Test
    void sameRequestIdSameParametersReturnsFirstResultWithoutNewVersion() {
        seedGroup();
        PlanVersionResponse first = planService.publish(
                new PublishPlanRequest("req-replay", GROUP, TURN, 100L));
        PlanVersionResponse replay = planService.publish(
                new PublishPlanRequest("req-replay", GROUP, TURN, 100L));

        assertThat(replay.planId()).isEqualTo(first.planId());
        assertThat(replay.versionNo()).isEqualTo(1);
        assertThat(replay.fingerprint()).isEqualTo(first.fingerprint());
        Integer versionCount = jdbc.queryForObject(
                "SELECT COUNT(1) FROM irr_plan_version WHERE request_id = 'req-replay'",
                Integer.class);
        assertThat(versionCount).isEqualTo(1);
    }

    @Test
    void sameRequestIdDifferentParametersReturns409AndCreatesNoVersion() {
        seedGroup();
        planService.publish(new PublishPlanRequest("req-conflict", GROUP, TURN, 100L));

        assertThatThrownBy(() -> planService.publish(
                new PublishPlanRequest("req-conflict", GROUP, TURN, 99L)))
                .isInstanceOf(ConflictException.class);

        Integer versionCount = jdbc.queryForObject(
                "SELECT COUNT(1) FROM irr_plan_version WHERE request_id = 'req-conflict'",
                Integer.class);
        assertThat(versionCount).isEqualTo(1);
        assertThat(planService.getEffective(TURN, GROUP).availableCubicMeter()).isEqualTo(100L);
    }

    @Test
    void sameRequestIdDifferentTurnReturns409() {
        seedGroup();
        planService.publish(new PublishPlanRequest("req-turn", GROUP, TURN, 100L));
        assertThatThrownBy(() -> planService.publish(
                new PublishPlanRequest("req-turn", GROUP, TURN + 1, 100L)))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void republishingWithNewRequestIdFreezesOldVersionAndOldVersionRemainsQueryable() {
        seedGroup();
        PlanVersionResponse v1 = planService.publish(
                new PublishPlanRequest("req-v1", GROUP, TURN, 100L));
        // 显式基于 v1 修订，新版本发布后旧版本冻结可查
        PlanVersionResponse v2 = planService.publish(
                new PublishPlanRequest("req-v2", GROUP, TURN, 200L, v1.versionNo()));

        assertThat(v2.versionNo()).isEqualTo(2);
        assertThat(v2.status()).isEqualTo("EFFECTIVE");
        assertThat(v2.availableCubicMeter()).isEqualTo(200L);

        // 旧版本冻结可查
        PlanVersionResponse oldV1 = planService.getVersion(TURN, GROUP, 1);
        assertThat(oldV1.status()).isEqualTo("SUPERSEDED");
        assertThat(oldV1.availableCubicMeter()).isEqualTo(100L);
        assertThat(oldV1.planId()).isEqualTo(v1.planId());

        assertThat(planService.listVersions(TURN, GROUP)).hasSize(2);
        assertThat(planService.getEffective(TURN, GROUP).planId()).isEqualTo(v2.planId());

        // 同轮同组恰有一个 EFFECTIVE
        Integer effectiveCount = jdbc.queryForObject("""
                SELECT COUNT(1) FROM irr_plan_version
                WHERE turn_no = ? AND group_code = ? AND status = 'EFFECTIVE'
                """, Integer.class, TURN, GROUP);
        assertThat(effectiveCount).isEqualTo(1);
    }

    @Test
    void publishWithoutTurnNoUsesCurrentUtcTurn() {
        seedGroup();
        PlanVersionResponse plan = planService.publish(
                new PublishPlanRequest("req-current", GROUP, null, 50L));
        assertThat(plan.turnNo()).isEqualTo(turnClock.currentTurnNo());
    }

    @Test
    void secondPublishWithoutBaseVersionReturns409AndCreatesNoVersion() {
        seedGroup();
        planService.publish(new PublishPlanRequest("req-first", GROUP, TURN, 100L));
        assertThatThrownBy(() -> planService.publish(
                new PublishPlanRequest("req-second-no-base", GROUP, TURN, 200L)))
                .isInstanceOf(ConflictException.class);
        Integer versionCount = jdbc.queryForObject(
                "SELECT COUNT(1) FROM irr_plan_version WHERE turn_no = ? AND group_code = ?",
                Integer.class, TURN, GROUP);
        assertThat(versionCount).isEqualTo(1);
        assertThat(planService.getEffective(TURN, GROUP).availableCubicMeter()).isEqualTo(100L);
    }

    @Test
    void reviseWithStaleBaseVersionReturns409AndKeepsCurrentEffective() {
        seedGroup();
        planService.publish(new PublishPlanRequest("req-v1", GROUP, TURN, 100L));
        planService.publish(new PublishPlanRequest("req-v2", GROUP, TURN, 200L, 1));
        // 当前生效已是 v2，再以 v1 为基线修订应 409
        assertThatThrownBy(() -> planService.publish(
                new PublishPlanRequest("req-v3-stale", GROUP, TURN, 300L, 1)))
                .isInstanceOf(ConflictException.class);
        assertThat(planService.getEffective(TURN, GROUP).availableCubicMeter()).isEqualTo(200L);
    }

    @Test
    void publishForUnknownGroupReturns404() {
        assertThatThrownBy(() -> planService.publish(
                new PublishPlanRequest("req-x", "NO-SUCH-GROUP", TURN, 10L)))
                .isInstanceOf(NotFoundException.class);
    }
}
