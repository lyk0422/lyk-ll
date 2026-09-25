package com.example.starter.flood;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.starter.flood.exception.FloodControlException;
import com.example.starter.flood.service.RoutingService;
import com.example.starter.flood.web.dto.RoutingView;
import com.example.starter.flood.web.dto.WarningView;
import java.math.BigDecimal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;

/**
 * 调洪演算 H2 集成测试：库容恰等临界、双指标任一判险、同键重放与异参 409。
 */
class RoutingIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private RoutingService routingService;
    @Autowired
    private FlowSupport flow;

    /** 合成曲线：水位 200m 对应蓄水 600000m3，高限 1000000m3，可用调洪库容恰 400000m3。 */
    @Test
    @DisplayName("库容恰等：设计洪水量=可用调洪库容 400000m3 判临界")
    void capacityExactlyCritical() {
        WarningView warning = flow.openWarning("w-critical");
        RoutingView view = flow.route("r-critical", warning.warningNo(),
                new BigDecimal("200.0000"), new BigDecimal("400000"),
                new BigDecimal("150"), new BigDecimal("10"));
        assertThat(view.availableCapacity()).isEqualByComparingTo("400000.0000");
        assertThat(view.currentStorage()).isEqualByComparingTo("600000.0000");
        assertThat(view.capacityJudgment()).isEqualTo("CRITICAL");
        assertThat(view.dangerJudgment()).isEqualTo("CRITICAL");
        assertThat(view.criterion()).contains("恰等=临界");
        assertThat(view.inputSnapshot()).contains("400000");
    }

    @Test
    @DisplayName("库容不足：设计洪水量小于可用库容判安全；超出判须泄流")
    void capacitySafeAndReleaseRequired() {
        WarningView warning = flow.openWarning("w-cap");
        RoutingView safe = flow.route("r-cap-safe", warning.warningNo(),
                new BigDecimal("200.0000"), new BigDecimal("399999"),
                new BigDecimal("150"), new BigDecimal("10"));
        assertThat(safe.capacityJudgment()).isEqualTo("SAFE");
        assertThat(safe.dangerJudgment()).isEqualTo("SAFE");

        RoutingView release = flow.route("r-cap-release", warning.warningNo(),
                new BigDecimal("200.0000"), new BigDecimal("400001"),
                new BigDecimal("150"), new BigDecimal("10"));
        assertThat(release.capacityJudgment()).isEqualTo("RELEASE_REQUIRED");
        assertThat(release.dangerJudgment()).isEqualTo("RELEASE_REQUIRED");
    }

    @Test
    @DisplayName("双指标任一低于下限判险：干滩 99m<100m 或浸润线 7.9m<8m，另一项安全不抵消")
    void eitherIndicatorDanger() {
        WarningView warning = flow.openWarning("w-danger");
        RoutingView dryBeachDanger = flow.route("r-danger-db", warning.warningNo(),
                new BigDecimal("190.0000"), new BigDecimal("100000"),
                new BigDecimal("99.000"), new BigDecimal("10.000"));
        assertThat(dryBeachDanger.dryBeachJudgment()).isEqualTo("DANGER");
        assertThat(dryBeachDanger.phreaticJudgment()).isEqualTo("SAFE");
        assertThat(dryBeachDanger.dangerJudgment()).isEqualTo("DANGER");

        RoutingView phreaticDanger = flow.route("r-danger-ph", warning.warningNo(),
                new BigDecimal("190.0000"), new BigDecimal("100000"),
                new BigDecimal("150.000"), new BigDecimal("7.900"));
        assertThat(phreaticDanger.phreaticJudgment()).isEqualTo("DANGER");
        assertThat(phreaticDanger.dryBeachJudgment()).isEqualTo("SAFE");
        assertThat(phreaticDanger.dangerJudgment()).isEqualTo("DANGER");
    }

    @Test
    @DisplayName("双指标恰等下限判临界，不判险")
    void indicatorsExactlyAtLimitCritical() {
        WarningView warning = flow.openWarning("w-indicator-critical");
        RoutingView view = flow.route("r-indicator-critical", warning.warningNo(),
                new BigDecimal("190.0000"), new BigDecimal("100000"),
                new BigDecimal("100.000"), new BigDecimal("8.000"));
        assertThat(view.dryBeachJudgment()).isEqualTo("CRITICAL");
        assertThat(view.phreaticJudgment()).isEqualTo("CRITICAL");
        assertThat(view.capacityJudgment()).isEqualTo("SAFE");
        assertThat(view.dangerJudgment()).isEqualTo("CRITICAL");
    }

    @Test
    @DisplayName("演算幂等：同键同参重放返回首次结果，同键异参返回 409")
    void routingIdempotency() {
        WarningView warning = flow.openWarning("w-idem");
        RoutingView first = flow.route("r-idem", warning.warningNo(),
                new BigDecimal("200.0000"), new BigDecimal("400000"),
                new BigDecimal("150"), new BigDecimal("10"));
        assertThat(first.replay()).isFalse();
        // 数值等价写法（150 与 150.0）指纹一致
        RoutingView replay = flow.route("r-idem", warning.warningNo(),
                new BigDecimal("200.0000"), new BigDecimal("400000.0000"),
                new BigDecimal("150.000"), new BigDecimal("10.000"));
        assertThat(replay.replay()).isTrue();
        assertThat(replay.routingNo()).isEqualTo(first.routingNo());

        assertThatThrownBy(() -> flow.route("r-idem", warning.warningNo(),
                new BigDecimal("200.0000"), new BigDecimal("400001"),
                new BigDecimal("150"), new BigDecimal("10")))
                .isInstanceOfSatisfying(FloodControlException.class, ex -> {
                    assertThat(ex.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(ex.getCode()).isEqualTo("IDEMPOTENT_CONFLICT");
                });
    }

    @Test
    @DisplayName("已归档处置链拒绝新演算（终态保护）")
    void routingAfterArchiveRejected() {
        WarningView warning = flow.openWarning("w-archived-routing");
        flow.archive("rv-archived-routing", warning.warningNo(), "无险情直接复盘");
        assertThatThrownBy(() -> flow.route("r-after-archive", warning.warningNo(),
                new BigDecimal("200.0000"), new BigDecimal("400000"), null, null))
                .isInstanceOf(FloodControlException.class)
                .hasMessageContaining("已归档");
    }

    @Test
    @DisplayName("演算可复算：按存库输入快照与判定口径重算，库容与综合判定与首次一致")
    void routingIsRecomputableFromSnapshot() throws Exception {
        WarningView warning = flow.openWarning("w-recompute");
        RoutingView first = flow.route("r-recompute", warning.warningNo(),
                new BigDecimal("205.0000"), new BigDecimal("199999"),
                new BigDecimal("99.5"), new BigDecimal("10"));

        // 从持久化快照重新取值并用同一判定口径复算
        com.example.starter.flood.repo.PondPO pond =
                new com.example.starter.flood.repo.PondRepository(jdbcTemplate)
                        .findByCode(flow.pondCode());
        com.fasterxml.jackson.databind.JsonNode snapshot =
                new com.fasterxml.jackson.databind.ObjectMapper().readTree(first.inputSnapshot());
        java.math.BigDecimal available = new java.math.BigDecimal(
                snapshot.get("availableCapacity").asText());
        java.math.BigDecimal design = new java.math.BigDecimal(
                snapshot.get("designFloodVolume").asText());
        java.math.BigDecimal dryBeach = new java.math.BigDecimal(
                snapshot.get("dryBeachLength").asText());
        java.math.BigDecimal phreatic = new java.math.BigDecimal(
                snapshot.get("phreaticDepth").asText());

        var capacityRecomputed = com.example.starter.flood.service.RoutingJudgment
                .capacity(design, available).name();
        var dbRecomputed = com.example.starter.flood.service.RoutingJudgment.indicator(
                dryBeach, pond.dryBeachLimit);
        var phRecomputed = com.example.starter.flood.service.RoutingJudgment.indicator(
                phreatic, pond.phreaticLimit);
        var dangerRecomputed = com.example.starter.flood.service.RoutingJudgment
                .combine(com.example.starter.flood.domain.CapacityJudgment
                                .valueOf(capacityRecomputed),
                        dbRecomputed, phRecomputed).name();

        assertThat(available).isEqualByComparingTo("200000.0000");
        assertThat(capacityRecomputed).isEqualTo(first.capacityJudgment());
        assertThat(dangerRecomputed).isEqualTo(first.dangerJudgment());
        assertThat(dangerRecomputed).isEqualTo("DANGER");
        assertThat(first.criterion()).contains("恰等=临界").contains("小于=险");
    }
}
