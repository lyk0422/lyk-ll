package com.example.starter.pump.business;

import com.example.starter.pump.error.ApiException;
import com.example.starter.pump.service.TideFacade;
import com.example.starter.pump.testsupport.AbstractIntegrationTest;
import com.example.starter.pump.web.dto.TideControlCreateRequest;
import com.example.starter.pump.web.dto.TideControlUpdateRequest;
import com.example.starter.pump.web.dto.TideIngestRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 潮汐控制配置与潮位上送：版本从1开始成功变更加1、恰达门槛进入强排、
 * 回差带保持、迟到较旧潮位只留证据、分区＋UTC时刻去重。
 */
class TideConfigAndIngestTest extends AbstractIntegrationTest {

    @Autowired
    private TideFacade tideFacade;

    @Test
    void createControl_startsAtVersion1_andDuplicateCreateRejected() {
        var created = tideFacade.createControl(
                new TideControlCreateRequest("tc-1", "ZN", new BigDecimal("4.500")));
        assertThat(created.version()).isEqualTo(1);
        assertThat(created.mode()).isEqualTo("NORMAL");
        assertThat(created.threshold()).isEqualByComparingTo("4.500");

        assertThatThrownBy(() -> tideFacade.createControl(
                new TideControlCreateRequest("tc-2", "ZN", new BigDecimal("5.000"))))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    ApiException api = (ApiException) ex;
                    assertThat(api.getStatus().value()).isEqualTo(422);
                    assertThat(api.getCode()).isEqualTo("TIDE_CONTROL_EXISTS");
                });
    }

    @Test
    void createControl_unknownZone_returns404() {
        assertThatThrownBy(() -> tideFacade.createControl(
                new TideControlCreateRequest("tc-z", "ZZ", new BigDecimal("4.500"))))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    ApiException api = (ApiException) ex;
                    assertThat(api.getStatus().value()).isEqualTo(404);
                    assertThat(api.getCode()).isEqualTo("ZONE_NOT_FOUND");
                });
    }

    @Test
    void updateThreshold_bumpsVersion_andMismatchReturns409WithCurrentVersion() {
        tideFacade.createControl(new TideControlCreateRequest("tc-1", "ZN", new BigDecimal("4.500")));

        var updated = tideFacade.updateThreshold("ZN",
                new TideControlUpdateRequest("tc-u1", new BigDecimal("4.800"), 1));
        assertThat(updated.version()).isEqualTo(2);
        assertThat(updated.threshold()).isEqualByComparingTo("4.800");

        // 版本失配：拒绝并提供新版本，配置保持不变
        assertThatThrownBy(() -> tideFacade.updateThreshold("ZN",
                new TideControlUpdateRequest("tc-u2", new BigDecimal("5.000"), 1)))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    ApiException api = (ApiException) ex;
                    assertThat(api.getStatus().value()).isEqualTo(409);
                    assertThat(api.getCode()).isEqualTo("VERSION_CONFLICT");
                    assertThat(api.getMessage()).contains("当前版本: 2");
                });
        var trajectory = tideFacade.trajectory("ZN");
        assertThat(trajectory.currentVersion()).isEqualTo(2);
    }

    @Test
    void updateThreshold_missingConfig_returns404() {
        assertThatThrownBy(() -> tideFacade.updateThreshold("ZN",
                new TideControlUpdateRequest("tc-u9", new BigDecimal("4.800"), 1)))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getStatus().value()).isEqualTo(404));
    }

    @Test
    void ingestTide_unconfiguredZone_returns404() {
        assertThatThrownBy(() -> tideFacade.ingestTide(new TideIngestRequest(
                "tr-0", "ZN", OffsetDateTime.parse("2026-09-26T00:00:00Z"),
                new BigDecimal("4.500"))))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    ApiException api = (ApiException) ex;
                    assertThat(api.getStatus().value()).isEqualTo(404);
                    assertThat(api.getCode()).isEqualTo("TIDE_CONTROL_NOT_FOUND");
                });
    }

    @Test
    void tideExactlyAtThreshold_generatesEnterForcedCandidate() {
        tideFacade.createControl(new TideControlCreateRequest("tc-1", "ZN", new BigDecimal("4.500")));

        // 低于门槛：不生成候选
        var below = tideFacade.ingestTide(new TideIngestRequest(
                "tr-1", "ZN", OffsetDateTime.parse("2026-09-26T00:00:00Z"),
                new BigDecimal("4.499")));
        assertThat(below.candidateId()).isNull();

        // 恰等门槛：进入强排，生成候选并固定当前版本1
        var at = tideFacade.ingestTide(new TideIngestRequest(
                "tr-2", "ZN", OffsetDateTime.parse("2026-09-26T00:05:00Z"),
                new BigDecimal("4.500")));
        assertThat(at.candidateId()).isNotNull();
        assertThat(at.direction()).isEqualTo("ENTER_FORCED");

        var detail = tideFacade.candidateDetail(at.candidateId());
        assertThat(detail.status()).isEqualTo("PENDING");
        assertThat(detail.pinnedVersion()).isEqualTo(1);
        // 固定全部相关站闸门目标（ZN 下 PS-01/PS-02 的启泵开度）
        assertThat(detail.targets()).hasSize(2);
        assertThat(detail.targets())
                .anySatisfy(t -> {
                    assertThat(t.stationCode()).isEqualTo("PS-01");
                    assertThat(t.targetOpening()).isEqualByComparingTo("80.00");
                })
                .anySatisfy(t -> {
                    assertThat(t.stationCode()).isEqualTo("PS-02");
                    assertThat(t.targetOpening()).isEqualByComparingTo("75.00");
                });

        // 已有待确认候选时，继续越限不重复生成
        var again = tideFacade.ingestTide(new TideIngestRequest(
                "tr-3", "ZN", OffsetDateTime.parse("2026-09-26T00:10:00Z"),
                new BigDecimal("4.900")));
        assertThat(again.candidateId()).isNull();
        assertThat(count("mode_switch_candidate")).isEqualTo(1);
    }

    @Test
    void lateOlderTide_keptAsEvidenceOnly_andDoesNotGenerateCandidate() {
        tideFacade.createControl(new TideControlCreateRequest("tc-1", "ZN", new BigDecimal("4.500")));

        // 先收到较新时刻（未越限）
        tideFacade.ingestTide(new TideIngestRequest(
                "tr-1", "ZN", OffsetDateTime.parse("2026-09-26T01:00:00Z"),
                new BigDecimal("4.100")));

        // 迟到的较旧潮位虽越限：只留证据，不生成候选、工况不倒退
        var late = tideFacade.ingestTide(new TideIngestRequest(
                "tr-2", "ZN", OffsetDateTime.parse("2026-09-26T00:30:00Z"),
                new BigDecimal("9.900")));
        assertThat(late.late()).isTrue();
        assertThat(late.candidateId()).isNull();
        assertThat(count("mode_switch_candidate")).isZero();
        assertThat(tideFacade.trajectory("ZN").currentMode()).isEqualTo("NORMAL");

        // 证据可查：迟到数据仍在潮位查询中并标记 late
        var readings = tideFacade.listReadings("ZN");
        assertThat(readings.readings()).hasSize(2);
        assertThat(readings.readings().get(0).late()).isTrue();
        assertThat(readings.readings().get(0).level()).isEqualByComparingTo("9.900");
    }

    @Test
    void sameZoneSameUtcInstant_deduplicates_evenAcrossOffsets() {
        tideFacade.createControl(new TideControlCreateRequest("tc-1", "ZN", new BigDecimal("4.500")));

        var first = tideFacade.ingestTide(new TideIngestRequest(
                "tr-1", "ZN", OffsetDateTime.parse("2026-09-26T08:00:00Z"),
                new BigDecimal("4.100")));
        // 同一 UTC 时刻不同时区表示：去重，返回首次ID
        var dup = tideFacade.ingestTide(new TideIngestRequest(
                "tr-2", "ZN", OffsetDateTime.parse("2026-09-26T10:00:00+02:00"),
                new BigDecimal("4.100")));
        assertThat(dup.deduplicated()).isTrue();
        assertThat(dup.readingId()).isEqualTo(first.readingId());
        assertThat(count("tide_reading")).isEqualTo(1);
    }
}
