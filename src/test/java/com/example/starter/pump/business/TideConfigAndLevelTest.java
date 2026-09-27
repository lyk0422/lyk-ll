package com.example.starter.pump.business;

import com.example.starter.pump.error.ApiException;
import com.example.starter.pump.service.PumpdownFacade;
import com.example.starter.pump.testsupport.AbstractIntegrationTest;
import com.example.starter.pump.web.dto.ArchiveRequest;
import com.example.starter.pump.web.dto.TideConfigRequest;
import com.example.starter.pump.web.dto.TideConfigResult;
import com.example.starter.pump.web.dto.TideLevelRequest;
import com.example.starter.pump.web.dto.TideLevelResult;
import com.example.starter.pump.web.dto.WarningRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 潮汐配置与潮位判定：版本从1开始成功变更加1、滞回进入/退出、
 * 迟到较旧潮位只留证据、已归档不可重开、未配置分区保持原行为。
 */
class TideConfigAndLevelTest extends AbstractIntegrationTest {

    private static final LocalDateTime T0 = LocalDateTime.of(2026, 9, 26, 8, 0);
    private static final Instant U1 = Instant.parse("2026-09-26T00:01:00Z");
    private static final Instant U2 = Instant.parse("2026-09-26T00:02:00Z");
    private static final Instant U3 = Instant.parse("2026-09-26T00:03:00Z");

    @Autowired
    private PumpdownFacade facade;

    private TideConfigResult createConfig(String requestId, String zone, String threshold) {
        return facade.handleTideConfig(
                new TideConfigRequest(requestId, zone, new BigDecimal(threshold), null));
    }

    @Test
    void createConfig_startsAtVersion1_updateIncrements() {
        TideConfigResult created = createConfig("tc-1", "ZN", "5.000");
        assertThat(created.version()).isEqualTo(1);
        assertThat(created.currentMode()).isEqualTo("NORMAL");
        assertThat(created.replayed()).isFalse();

        TideConfigResult updated = facade.handleTideConfig(
                new TideConfigRequest("tc-2", "ZN", new BigDecimal("5.500"), 1));
        assertThat(updated.version()).isEqualTo(2);
        assertThat(updated.backwaterThreshold()).isEqualByComparingTo("5.500");

        var view = facade.tideConfig("ZN");
        assertThat(view.version()).isEqualTo(2);
        assertThat(view.currentMode()).isEqualTo("NORMAL");
    }

    @Test
    void updateConfig_versionMismatch_rejected409WithCurrentVersion() {
        createConfig("tc-3", "ZN", "5.000");

        assertThatThrownBy(() -> facade.handleTideConfig(
                new TideConfigRequest("tc-4", "ZN", new BigDecimal("6.000"), 7)))
                .isInstanceOfSatisfying(ApiException.class, ex -> {
                    assertThat(ex.getStatus().value()).isEqualTo(409);
                    assertThat(ex.getCode()).isEqualTo("VERSION_CONFLICT");
                    assertThat(ex.getCurrentVersion()).isEqualTo(1);
                });
        // 失败不占键：同 requestId 修正版本后可重新受理
        TideConfigResult retried = facade.handleTideConfig(
                new TideConfigRequest("tc-4", "ZN", new BigDecimal("6.000"), 1));
        assertThat(retried.version()).isEqualTo(2);
    }

    @Test
    void updateConfig_onUnconfiguredZone_returns404() {
        assertThatThrownBy(() -> facade.handleTideConfig(
                new TideConfigRequest("tc-5", "ZN", new BigDecimal("5.000"), 1)))
                .isInstanceOfSatisfying(ApiException.class, ex -> {
                    assertThat(ex.getStatus().value()).isEqualTo(404);
                    assertThat(ex.getCode()).isEqualTo("TIDE_CONFIG_NOT_FOUND");
                });
    }

    @Test
    void configOnUnknownZone_returns404() {
        assertThatThrownBy(() -> facade.handleTideConfig(
                new TideConfigRequest("tc-6", "ZZ", new BigDecimal("5.000"), null)))
                .isInstanceOfSatisfying(ApiException.class, ex -> {
                    assertThat(ex.getStatus().value()).isEqualTo(404);
                    assertThat(ex.getCode()).isEqualTo("ZONE_NOT_FOUND");
                });
    }

    @Test
    void tideLevel_unconfiguredZone_rejected422_keepsOriginalBehavior() {
        assertThatThrownBy(() -> facade.handleTideLevel(
                new TideLevelRequest("tl-0", "ZN", new BigDecimal("5.000"), U1, 1)))
                .isInstanceOfSatisfying(ApiException.class, ex -> {
                    assertThat(ex.getStatus().value()).isEqualTo(422);
                    assertThat(ex.getCode()).isEqualTo("TIDE_NOT_CONFIGURED");
                });
        assertThat(count("tide_level_reading")).isZero();
    }

    @Test
    void exactThreshold_entersForced_andGeneratesCandidate() {
        createConfig("tc-7", "ZN", "5.000");

        // 恰等门槛进入强排
        TideLevelResult result = facade.handleTideLevel(
                new TideLevelRequest("tl-1", "ZN", new BigDecimal("5.000"), U1, 1));

        assertThat(result.accepted()).isTrue();
        assertThat(result.decision()).isEqualTo("ENTER_FORCED");
        assertThat(result.candidateId()).isNotNull();
        assertThat(result.currentVersion()).isEqualTo(1);

        var candidate = facade.tideCandidate(result.candidateId());
        assertThat(candidate.targetMode()).isEqualTo("FORCED");
        assertThat(candidate.status()).isEqualTo("PENDING");
        assertThat(candidate.tideLevel()).isEqualByComparingTo("5.000");
        assertThat(candidate.tideObservedAt()).isEqualTo(U1);
        assertThat(candidate.thresholdSnapshot()).isEqualByComparingTo("5.000");
        assertThat(candidate.baseVersion()).isEqualTo(1);
        // 固定全部相关站闸门目标：进入强排取站点联动开度
        assertThat(candidate.stations()).hasSize(2);
        assertThat(candidate.stations().get(0).stationCode()).isEqualTo("PS-01");
        assertThat(candidate.stations().get(0).targetOpening()).isEqualByComparingTo("80.00");
        assertThat(candidate.stations().get(1).stationCode()).isEqualTo("PS-02");
        assertThat(candidate.stations().get(1).targetOpening()).isEqualByComparingTo("75.00");
        // 候选生成不改变工况与版本
        assertThat(facade.tideConfig("ZN").currentMode()).isEqualTo("NORMAL");
        assertThat(facade.tideConfig("ZN").version()).isEqualTo(1);
    }

    @Test
    void belowThreshold_noDecision_noCandidate() {
        createConfig("tc-8", "ZN", "5.000");

        TideLevelResult result = facade.handleTideLevel(
                new TideLevelRequest("tl-2", "ZN", new BigDecimal("4.999"), U1, 1));

        assertThat(result.decision()).isEqualTo("NONE");
        assertThat(result.candidateId()).isNull();
        assertThat(count("tide_switch_candidate")).isZero();
    }

    @Test
    void sameZoneSameUtcTime_deduplicated() {
        createConfig("tc-9", "ZN", "5.000");
        TideLevelResult first = facade.handleTideLevel(
                new TideLevelRequest("tl-3", "ZN", new BigDecimal("4.000"), U1, 1));

        TideLevelResult dup = facade.handleTideLevel(
                new TideLevelRequest("tl-4", "ZN", new BigDecimal("4.000"), U1, 1));

        assertThat(dup.deduplicated()).isTrue();
        assertThat(dup.accepted()).isFalse();
        assertThat(dup.readingId()).isEqualTo(first.readingId());
        assertThat(count("tide_level_reading")).isEqualTo(1);
    }

    @Test
    void lateOutOfOrderTide_onlyEvidence_noModeRegression() {
        createConfig("tc-10", "ZN", "5.000");
        facade.handleTideLevel(new TideLevelRequest("tl-5", "ZN", new BigDecimal("4.000"), U2, 1));

        // 较旧时刻迟到：只留证据，不判定、不生成候选
        TideLevelResult late = facade.handleTideLevel(
                new TideLevelRequest("tl-6", "ZN", new BigDecimal("9.999"), U1, 1));

        assertThat(late.late()).isTrue();
        assertThat(late.accepted()).isFalse();
        assertThat(late.decision()).isEqualTo("NONE");
        assertThat(late.candidateId()).isNull();
        assertThat(count("tide_level_reading")).isEqualTo(1);
        assertThat(count("late_tide_event")).isEqualTo(1);
        assertThat(count("tide_switch_candidate")).isZero();

        var view = facade.tideLevels("ZN");
        assertThat(view.readings()).hasSize(1);
        assertThat(view.lateEvents()).hasSize(1);
        assertThat(view.lateEvents().get(0).reason()).isEqualTo("OUT_OF_ORDER");
        assertThat(view.lateEvents().get(0).observedAt()).isEqualTo(U1);
    }

    @Test
    void archivedEvent_tideOnlyEvidence_cannotReopen() {
        createConfig("tc-11", "ZN", "5.000");
        Long id = facade.handleWarning(new WarningRequest("w-t1", "ZN", "ORANGE", T0)).pumpdownId();
        facade.handleArchive(new ArchiveRequest("a-t1", id, T0.plusHours(2)));
        clock.setNow(T0.plusHours(3));

        TideLevelResult late = facade.handleTideLevel(
                new TideLevelRequest("tl-7", "ZN", new BigDecimal("9.000"), U3, 1));

        assertThat(late.late()).isTrue();
        assertThat(late.decision()).isEqualTo("NONE");
        assertThat(count("tide_level_reading")).isZero();
        assertThat(count("late_tide_event")).isEqualTo(1);
        assertThat(count("tide_switch_candidate")).isZero();
        assertThat(facade.tideLevels("ZN").lateEvents().get(0).reason()).isEqualTo("ARCHIVED");
        // 已归档事件不可重开
        assertThat(facade.replay(id).status()).isEqualTo("ARCHIVED");
    }

    @Test
    void tideLevel_versionMismatch_rejected409_andFailureDoesNotHoldKey() {
        createConfig("tc-12", "ZN", "5.000");

        assertThatThrownBy(() -> facade.handleTideLevel(
                new TideLevelRequest("tl-8", "ZN", new BigDecimal("4.000"), U1, 9)))
                .isInstanceOfSatisfying(ApiException.class, ex -> {
                    assertThat(ex.getStatus().value()).isEqualTo(409);
                    assertThat(ex.getCode()).isEqualTo("VERSION_CONFLICT");
                    assertThat(ex.getCurrentVersion()).isEqualTo(1);
                });
        // 失败不占键：同 requestId 修正参数后可重新受理
        TideLevelResult retried = facade.handleTideLevel(
                new TideLevelRequest("tl-8", "ZN", new BigDecimal("4.000"), U1, 1));
        assertThat(retried.accepted()).isTrue();
        assertThat(count("tide_level_reading")).isEqualTo(1);
    }

    @Test
    void newTideFalsifiesPendingCandidate_superseded() {
        createConfig("tc-13", "ZN", "5.000");
        TideLevelResult entered = facade.handleTideLevel(
                new TideLevelRequest("tl-9", "ZN", new BigDecimal("5.500"), U1, 1));
        Long candidateId = entered.candidateId();
        assertThat(candidateId).isNotNull();

        // 跌破门槛：进入前提失效，待确认候选被取代，不生成反向候选
        TideLevelResult dropped = facade.handleTideLevel(
                new TideLevelRequest("tl-10", "ZN", new BigDecimal("4.000"), U2, 1));

        assertThat(dropped.decision()).isEqualTo("NONE");
        assertThat(dropped.candidateId()).isNull();
        assertThat(facade.tideCandidate(candidateId).status()).isEqualTo("SUPERSEDED");
        assertThat(count("tide_switch_candidate")).isEqualTo(1);
    }

    @Test
    void sameDirectionPendingCandidate_reused() {
        createConfig("tc-14", "ZN", "5.000");
        TideLevelResult first = facade.handleTideLevel(
                new TideLevelRequest("tl-11", "ZN", new BigDecimal("5.100"), U1, 1));

        TideLevelResult second = facade.handleTideLevel(
                new TideLevelRequest("tl-12", "ZN", new BigDecimal("5.200"), U2, 1));

        assertThat(second.decision()).isEqualTo("ENTER_FORCED");
        assertThat(second.candidateId()).isEqualTo(first.candidateId());
        assertThat(count("tide_switch_candidate")).isEqualTo(1);
    }
}
