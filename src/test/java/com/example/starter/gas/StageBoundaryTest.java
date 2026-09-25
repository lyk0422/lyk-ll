package com.example.starter.gas;

import com.example.starter.gas.service.GasStationService;
import com.example.starter.gas.web.dto.DetectionRequest;
import com.example.starter.gas.web.dto.LinkageRequest;
import com.example.starter.gas.web.dto.LinkageResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 阶段衔接边界：锁阀期间联动加注为零；封存后迟到联动仅登记事件。
 */
@Import(FixedClockConfig.class)
class StageBoundaryTest extends AbstractGasIntegrationTest {

    private static final LocalDate DAY = LocalDate.of(2026, 9, 26);

    @Autowired
    private GasStationService service;

    @Test
    void linkageWhileValveLockedInjectsNothingButKeepsTheory() {
        // 连续两次越限锁阀
        service.detect(new DetectionRequest("B-1", STATION, DAY.atTime(8, 0),
                new BigDecimal("25"), new BigDecimal("0"), null));
        service.detect(new DetectionRequest("B-2", STATION, DAY.atTime(8, 5),
                new BigDecimal("25"), new BigDecimal("0"), null));
        assertThat(stationSnapshot().get("valve_state")).isEqualTo("LOCKED");

        // 锁阀期间发生低压联动：调压照常，实际加注为 0、理论仍复算
        LinkageResponse resp = service.regulate(new LinkageRequest("B-L", STATION, DAY.atTime(9, 0),
                new BigDecimal("180"), new BigDecimal("1000"), null));
        assertThat(resp.pressureAction()).isEqualTo("RAISE");
        assertThat(resp.injection().valveLocked()).isTrue();
        assertThat(resp.injection().theoreticalConsumeG()).isEqualByComparingTo("20.000000");
        assertThat(resp.injection().actualConsumeG()).isEqualByComparingTo("0");
        assertThat((BigDecimal) stationSnapshot().get("odor_consumed")).isEqualByComparingTo("0");
    }

    @Test
    void linkageAfterSealIsRecordedAsLateOnly() {
        // 先产生一条正常数据并封存
        service.detect(new DetectionRequest("B-D", STATION, DAY.atTime(8, 0),
                new BigDecimal("20"), new BigDecimal("1000"), new BigDecimal("20.000000")));
        assertThat(service.settle(new com.example.starter.gas.web.dto.SettlementRequest(
                "B-S", STATION, DAY)).status()).isEqualTo("SEALED");

        LinkageResponse late = service.regulate(new LinkageRequest("B-LATE", STATION, DAY.atTime(22, 0),
                new BigDecimal("180"), new BigDecimal("800"), null));
        assertThat(late.acceptedAsLate()).isTrue();
        assertThat(countOf("late_detection_event", "request_id = ?", "B-LATE")).isEqualTo(1);
        assertThat(countOf("pressure_detection", "request_id = ?", "B-LATE")).isZero();
        assertThat(countOf("odorant_injection", "source_request_id = ?", "B-LATE")).isZero();
        assertThat(countOf("flow_reading", "request_id = ?", "B-LATE")).isZero();
    }
}
