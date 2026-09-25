package com.example.starter.waterlogging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;

import com.example.starter.waterlogging.api.PlanView;
import com.example.starter.waterlogging.api.WarningStartRequest;
import com.example.starter.waterlogging.domain.PlanStatus;
import com.example.starter.waterlogging.domain.PumpAction;
import com.example.starter.waterlogging.exception.ConflictException;
import com.example.starter.waterlogging.service.WaterLoggingService;
import com.example.starter.waterlogging.web.IdempotentResponse;

/**
 * 多站流域分区联动：同一分区同时刻只允许一套启停序列；不同分区互不影响。
 */
class ZoneSequenceTest extends AbstractH2IntegrationTest {

    private static final LocalDateTime T0 = LocalDateTime.of(2026, 9, 26, 8, 0, 0);

    @Autowired
    private WaterLoggingService service;

    private WarningStartRequest warning(String requestId, String zone, LocalDateTime time) {
        return new WarningStartRequest(requestId, "ORANGE", zone, "东湾河",
                new BigDecimal("3.00"), time, new BigDecimal("60.00"));
    }

    @Test
    void onlyOneActiveSequencePerZoneWhileOtherZonesAreIndependent() {
        IdempotentResponse<PlanView> first =
                service.startOnWarning(warning("WARN-ZONE-1", "DONGWAN", T0));
        assertThat(first.replay()).isFalse();

        // 同分区第二套序列：409，不新增预降单、不改变泵站。
        assertThatThrownBy(() -> service.startOnWarning(warning("WARN-ZONE-2", "DONGWAN", T0.plusMinutes(1))))
                .isInstanceOf(ConflictException.class)
                .satisfies(ex -> assertThat(((ConflictException) ex).getStatus())
                        .isEqualTo(HttpStatus.CONFLICT));
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM pre_drain_plan WHERE basin_zone = 'DONGWAN'", Integer.class))
                .isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM zone_sequence_lock WHERE basin_zone = 'DONGWAN'", Integer.class))
                .isEqualTo(1);

        // 不同分区可独立联动。
        var otherZone = service.startOnWarning(warning("WARN-ZONE-3", "XIHU", T0.plusMinutes(2)));
        assertThat(otherZone.data().status()).isEqualTo(PlanStatus.DRAINING);
        assertThat(otherZone.data().stations()).singleElement()
                .satisfies(station -> {
                    assertThat(station.stationCode()).isEqualTo("XH-01");
                    assertThat(station.runStatus()).isEqualTo(PumpAction.RUNNING);
                });
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM zone_sequence_lock", Integer.class)).isEqualTo(2);
    }
}
