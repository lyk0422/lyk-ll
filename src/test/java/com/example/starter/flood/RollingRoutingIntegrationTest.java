package com.example.starter.flood;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.starter.flood.exception.FloodControlException;
import com.example.starter.flood.service.RollingRoutingService;
import com.example.starter.flood.web.dto.InflowPoint;
import com.example.starter.flood.web.dto.PlanView;
import com.example.starter.flood.web.dto.RollingRoutingRequest;
import com.example.starter.flood.web.dto.RollingRoutingView;
import com.example.starter.flood.web.dto.RoutingCompareView;
import com.example.starter.flood.web.dto.RoutingVersionView;
import com.example.starter.flood.web.dto.RoutingView;
import com.example.starter.flood.web.dto.WarningView;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;

/**
 * 滚动演算 H2 集成测试：恰满 30 分钟触发、版本递增与旧版本可查、
 * 泄流能力不足时同事务升级预警并追加方案版本、在途指令钉版、幂等与复算。
 */
class RollingRoutingIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private RollingRoutingService rollingService;
    @Autowired
    private FlowSupport flow;

    /** 恒定流量过程线：rate m3/s 持续 minutes 分钟。 */
    private static List<InflowPoint> hydrograph(String rate, int minutes) {
        return List.of(new InflowPoint(0, new BigDecimal(rate)),
                new InflowPoint(minutes, new BigDecimal(rate)));
    }

    private RollingRoutingView roll(String requestId, String warningNo,
                                    List<InflowPoint> hydrograph) {
        return rollingService.roll(new RollingRoutingRequest(requestId, warningNo,
                hydrograph, null, null));
    }

    @Test
    @DisplayName("恰满 30 分钟触发：29 分钟拒绝（ROLLING_NOT_DUE），恰 30 分钟生成新版本，旧版本保留可查")
    void rollingTriggersExactlyAtInterval() {
        WarningView warning = flow.openWarning("w-roll-boundary");
        RoutingView v1 = flow.route("r-roll-boundary", warning.warningNo(),
                new BigDecimal("200.0000"), new BigDecimal("100000"),
                new BigDecimal("150"), new BigDecimal("10"));

        clock.advanceMinutes(29);
        assertThatThrownBy(() -> roll("rr-boundary-1", warning.warningNo(),
                hydrograph("100", 30)))
                .isInstanceOfSatisfying(FloodControlException.class, ex -> {
                    assertThat(ex.getCode()).isEqualTo("ROLLING_NOT_DUE");
                    assertThat(ex.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                });
        // 未触发不产生新版本
        assertThat(rollingService.listVersions(warning.warningNo())).hasSize(1);

        clock.advanceMinutes(1);
        RollingRoutingView v2 = roll("rr-boundary-1", warning.warningNo(),
                hydrograph("100", 30));
        assertThat(v2.replay()).isFalse();
        assertThat(v2.version()).isEqualTo(2);
        assertThat(v2.triggerType()).isEqualTo("ROLLING");
        assertThat(v2.prevRoutingNo()).isEqualTo(v1.routingNo());
        // 100 m3/s × 1800s = 180000 m3；蓄水 600000+180000=780000；水位反查 204.5m
        assertThat(v2.inflowVolume()).isEqualByComparingTo("180000.0000");
        assertThat(v2.releasedVolume()).isEqualByComparingTo("0.0000");
        assertThat(v2.currentStorage()).isEqualByComparingTo("780000.0000");
        assertThat(v2.currentWaterLevel()).isEqualByComparingTo("204.5000");
        assertThat(v2.availableCapacity()).isEqualByComparingTo("220000.0000");
        assertThat(v2.capacityJudgment()).isEqualTo("SAFE");
        assertThat(v2.warningEscalated()).isFalse();

        // 旧版本保留可查
        List<RoutingVersionView> versions = rollingService.listVersions(warning.warningNo());
        assertThat(versions).hasSize(2);
        assertThat(versions.get(0).version()).isEqualTo(1);
        assertThat(versions.get(0).triggerType()).isEqualTo("MANUAL");
        assertThat(versions.get(1).version()).isEqualTo(2);
        RoutingVersionView old = rollingService.getVersion(v1.routingNo());
        assertThat(old.version()).isEqualTo(1);
        assertThat(old.inputSnapshot()).contains("100000");
    }

    @Test
    @DisplayName("泄流能力不足：同事务提升预警级别并追加方案版本，在途指令钉住旧版本，生成替代建议")
    void insufficientCapacityEscalatesAndAppendsPlanVersion() {
        WarningView warning = flow.openWarning("w-roll-escalate");
        RoutingView v1 = flow.route("r-roll-escalate", warning.warningNo(),
                new BigDecimal("200.0000"), new BigDecimal("400001"),
                new BigDecimal("150"), new BigDecimal("10"));
        PlanView plan = flow.createPlan("p-roll-escalate", warning.warningNo(),
                v1.routingNo(), FlowSupport.safeBoundaryOpenings());

        clock.advanceMinutes(30);
        RollingRoutingView v2 = roll("rr-escalate", warning.warningNo(),
                hydrograph("300", 30));

        // 入库 300×1800=540000，泄流 20×1800=36000，蓄水 600000+540000-36000=1104000 越限钳制到 1000000
        assertThat(v2.inflowVolume()).isEqualByComparingTo("540000.0000");
        assertThat(v2.releasedVolume()).isEqualByComparingTo("36000.0000");
        assertThat(v2.currentStorage()).isEqualByComparingTo("1000000.0000");
        assertThat(v2.availableCapacity()).isEqualByComparingTo("0.0000");
        assertThat(v2.capacityJudgment()).isEqualTo("RELEASE_REQUIRED");
        assertThat(v2.warningEscalated()).isTrue();
        assertThat(v2.warningLevel()).isEqualTo("RED");
        assertThat(v2.appendedPlanNo()).isNotNull();
        assertThat(v2.appendedPlanVersion()).isEqualTo(2);
        assertThat(v2.conflictAdviceNo()).isNotNull();

        // 同事务结果：级别、方案版本、旧方案留痕一次性可见
        String level = jdbcTemplate.queryForObject(
                "SELECT warning_level FROM warning_chain WHERE warning_no = ?",
                String.class, warning.warningNo());
        assertThat(level).isEqualTo("RED");
        Integer escalateEvents = jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM warning_event WHERE event_type = 'LEVEL_ESCALATED'",
                Integer.class);
        assertThat(escalateEvents).isEqualTo(1);

        String oldPlanStatus = jdbcTemplate.queryForObject(
                "SELECT status FROM release_plan WHERE plan_no = ?", String.class, plan.planNo());
        assertThat(oldPlanStatus).isEqualTo("SUPERSEDED");
        String newPlanStatus = jdbcTemplate.queryForObject(
                "SELECT status FROM release_plan WHERE plan_no = ?",
                String.class, v2.appendedPlanNo());
        assertThat(newPlanStatus).isEqualTo("EFFECTIVE");
        BigDecimal newTotal = jdbcTemplate.queryForObject(
                "SELECT total_release FROM release_plan WHERE plan_no = ?",
                BigDecimal.class, v2.appendedPlanNo());
        assertThat(newTotal).isEqualByComparingTo("20.000");

        // 在途指令只认创建时的方案版本：仍为 ISSUED 且钉住版本 1
        String oldOrderStatus = jdbcTemplate.queryForObject(
                "SELECT status FROM release_order WHERE order_no = ?",
                String.class, plan.orderNo());
        assertThat(oldOrderStatus).isEqualTo("ISSUED");
        Integer pinnedVersion = jdbcTemplate.queryForObject(
                "SELECT plan_version FROM release_order WHERE order_no = ?",
                Integer.class, plan.orderNo());
        assertThat(pinnedVersion).isEqualTo(1);
        // 冲突未自动下达新指令：等待人工确认
        Integer orderCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM release_order", Integer.class);
        assertThat(orderCount).isEqualTo(1);

        String adviceStatus = jdbcTemplate.queryForObject(
                "SELECT status FROM order_conflict_advice WHERE advice_no = ?",
                String.class, v2.conflictAdviceNo());
        assertThat(adviceStatus).isEqualTo("PENDING");
    }

    @Test
    @DisplayName("滚动演算幂等：同键同参重放首次版本（不新增版本），同键异参 409")
    void rollingIdempotency() {
        WarningView warning = flow.openWarning("w-roll-idem");
        flow.route("r-roll-idem", warning.warningNo(),
                new BigDecimal("200.0000"), new BigDecimal("100000"),
                new BigDecimal("150"), new BigDecimal("10"));
        clock.advanceMinutes(30);

        RollingRoutingView first = roll("rr-idem", warning.warningNo(), hydrograph("100", 30));
        assertThat(first.replay()).isFalse();

        // 再推进 30 分钟后同键同参重放：仍返回首次结果，不生成新版本
        clock.advanceMinutes(30);
        RollingRoutingView replay = roll("rr-idem", warning.warningNo(),
                hydrograph("100.0", 30));
        assertThat(replay.replay()).isTrue();
        assertThat(replay.routingNo()).isEqualTo(first.routingNo());
        assertThat(rollingService.listVersions(warning.warningNo())).hasSize(2);

        assertThatThrownBy(() -> roll("rr-idem", warning.warningNo(), hydrograph("200", 30)))
                .isInstanceOfSatisfying(FloodControlException.class, ex -> {
                    assertThat(ex.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(ex.getCode()).isEqualTo("IDEMPOTENT_CONFLICT");
                });
        assertThat(rollingService.listVersions(warning.warningNo())).hasSize(2);
    }

    @Test
    @DisplayName("演算版本对比：差量为后版减前版；跨处置链对比拒绝")
    void compareVersions() {
        WarningView warning = flow.openWarning("w-roll-compare");
        RoutingView v1 = flow.route("r-roll-compare", warning.warningNo(),
                new BigDecimal("200.0000"), new BigDecimal("100000"),
                new BigDecimal("150"), new BigDecimal("10"));
        clock.advanceMinutes(30);
        RollingRoutingView v2 = roll("rr-compare", warning.warningNo(), hydrograph("100", 30));

        RoutingCompareView compare = rollingService.compare(v1.routingNo(), v2.routingNo());
        assertThat(compare.from().version()).isEqualTo(1);
        assertThat(compare.to().version()).isEqualTo(2);
        assertThat(compare.waterLevelDelta()).isEqualByComparingTo("4.5000");
        assertThat(compare.storageDelta()).isEqualByComparingTo("180000.0000");
        assertThat(compare.availableCapacityDelta()).isEqualByComparingTo("-180000.0000");
        assertThat(compare.designFloodVolumeDelta()).isEqualByComparingTo("80000.0000");
        assertThat(compare.capacityJudgmentChange()).isEqualTo("SAFE->SAFE");

        WarningView other = flow.openWarning("w-roll-compare-other");
        RoutingView otherRouting = flow.route("r-roll-compare-other", other.warningNo(),
                new BigDecimal("200.0000"), new BigDecimal("100000"),
                new BigDecimal("150"), new BigDecimal("10"));
        assertThatThrownBy(() -> rollingService.compare(v1.routingNo(), otherRouting.routingNo()))
                .isInstanceOfSatisfying(FloodControlException.class,
                        ex -> assertThat(ex.getCode()).isEqualTo("COMPARE_DIFFERENT_SERIES"));
    }

    @Test
    @DisplayName("滚动版本保留源输入快照：按快照可单独复算蓄水、水位与判定")
    void rollingVersionRecomputableFromSnapshot() throws Exception {
        WarningView warning = flow.openWarning("w-roll-recompute");
        flow.route("r-roll-recompute", warning.warningNo(),
                new BigDecimal("200.0000"), new BigDecimal("100000"),
                new BigDecimal("150"), new BigDecimal("10"));
        clock.advanceMinutes(30);
        RollingRoutingView v2 = roll("rr-recompute", warning.warningNo(),
                hydrograph("100", 30));

        com.fasterxml.jackson.databind.JsonNode snap =
                new com.fasterxml.jackson.databind.ObjectMapper().readTree(v2.inputSnapshot());
        BigDecimal prevStorage = new BigDecimal(snap.get("prevCurrentStorage").asText());
        BigDecimal inflow = new BigDecimal(snap.get("inflowVolume").asText());
        BigDecimal released = new BigDecimal(snap.get("releasedVolume").asText());
        BigDecimal recomputedStorage = prevStorage.add(inflow).subtract(released);
        assertThat(recomputedStorage).isEqualByComparingTo("780000.0000");
        assertThat(recomputedStorage).isEqualByComparingTo(v2.currentStorage());

        var pond = new com.example.starter.flood.repo.PondRepository(jdbcTemplate)
                .findByCode(flow.pondCode());
        var points = new com.example.starter.flood.repo.PondRepository(jdbcTemplate)
                .findCurvePoints(pond.id);
        BigDecimal recomputedLevel = new com.example.starter.flood.service
                .StorageCurveCalculator().levelAt(points, recomputedStorage);
        assertThat(recomputedLevel).isEqualByComparingTo(v2.currentWaterLevel());
        assertThat(snap.get("hydrograph").toString()).contains("100");
        assertThat(snap.get("prevRoutingNo").asText()).isNotBlank();
    }

    @Test
    @DisplayName("滚动前置校验：无静态演算版本 422；已归档处置链 422；过程线首点非 0 或乱序 422")
    void rollingPreconditions() {
        WarningView warning = flow.openWarning("w-roll-pre");
        assertThatThrownBy(() -> roll("rr-pre-1", warning.warningNo(), hydrograph("100", 30)))
                .isInstanceOfSatisfying(FloodControlException.class,
                        ex -> assertThat(ex.getCode()).isEqualTo("ROLLING_NO_BASE"));

        RoutingView v1 = flow.route("r-roll-pre", warning.warningNo(),
                new BigDecimal("200.0000"), new BigDecimal("100000"),
                new BigDecimal("150"), new BigDecimal("10"));
        clock.advanceMinutes(30);
        assertThatThrownBy(() -> roll("rr-pre-2", warning.warningNo(),
                List.of(new InflowPoint(5, new BigDecimal("100")),
                        new InflowPoint(30, new BigDecimal("100")))))
                .isInstanceOfSatisfying(FloodControlException.class,
                        ex -> assertThat(ex.getCode()).isEqualTo("HYDROGRAPH_INVALID"));
        assertThatThrownBy(() -> roll("rr-pre-3", warning.warningNo(),
                List.of(new InflowPoint(0, new BigDecimal("100")),
                        new InflowPoint(0, new BigDecimal("100")))))
                .isInstanceOfSatisfying(FloodControlException.class,
                        ex -> assertThat(ex.getCode()).isEqualTo("HYDROGRAPH_INVALID"));

        flow.archive("rv-roll-pre", warning.warningNo(), "归档后禁止滚动");
        assertThatThrownBy(() -> roll("rr-pre-4", warning.warningNo(), hydrograph("100", 30)))
                .isInstanceOfSatisfying(FloodControlException.class,
                        ex -> assertThat(ex.getCode()).isEqualTo("CHAIN_ARCHIVED"));
        assertThat(rollingService.listVersions(warning.warningNo())).hasSize(1);
        assertThat(v1.routingNo()).isNotBlank();
    }
}
