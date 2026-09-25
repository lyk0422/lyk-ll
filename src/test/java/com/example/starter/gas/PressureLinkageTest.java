package com.example.starter.gas;

import com.example.starter.gas.service.GasStationService;
import com.example.starter.gas.web.TaskSlotConflictException;
import com.example.starter.gas.web.dto.LinkageRequest;
import com.example.starter.gas.web.dto.LinkageResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 压力调节联动：恰等限值合格、越限调压、加注同事务下发、同刻唯一受理、幂等。
 */
@Import(FixedClockConfig.class)
class PressureLinkageTest extends AbstractGasIntegrationTest {

    private static final LocalDateTime T = LocalDateTime.of(2026, 9, 26, 10, 0, 0);

    @Autowired
    private GasStationService service;

    private LinkageRequest req(String requestId, LocalDateTime at, String pressure, String flow) {
        return new LinkageRequest(requestId, STATION, at, new BigDecimal(pressure),
                new BigDecimal(flow), null);
    }

    @Test
    void pressureExactlyAtLimitsIsQualifiedAndHolds() {
        // 恰等下限 200 与恰等上限 400 均合格，不调压
        LinkageResponse low = service.regulate(req("L-LOW", T, "200", "100"));
        assertThat(low.pressureAction()).isEqualTo("HOLD");
        assertThat(low.regulated()).isFalse();

        LinkageResponse high = service.regulate(req("L-HIGH", T.plusMinutes(1), "400", "100"));
        assertThat(high.pressureAction()).isEqualTo("HOLD");
        assertThat(high.regulated()).isFalse();
    }

    @Test
    void belowLowRaisesAndAboveHighLowers() {
        LinkageResponse raise = service.regulate(req("L-RAISE", T, "199.99", "100"));
        assertThat(raise.pressureAction()).isEqualTo("RAISE");
        assertThat(raise.regulated()).isTrue();

        LinkageResponse lower = service.regulate(req("L-LOWER", T.plusMinutes(1), "400.01", "100"));
        assertThat(lower.pressureAction()).isEqualTo("LOWER");
        assertThat(lower.regulated()).isTrue();
    }

    @Test
    void regulationAndInjectionArePersistedTogether() {
        LinkageResponse resp = service.regulate(req("L-TX", T, "180", "200"));

        // 调压记录、流量、投加调整在同一事务内全部落库
        assertThat(countOf("pressure_detection", "request_id = ?", "L-TX")).isEqualTo(1);
        assertThat(countOf("flow_reading", "request_id = ?", "L-TX")).isEqualTo(1);
        assertThat(countOf("odorant_injection", "source_request_id = ?", "L-TX")).isEqualTo(1);
        // 200 m³ * 0.02 g/m³ = 4g 理论/实耗
        assertThat(resp.injection().theoreticalConsumeG()).isEqualByComparingTo("4.000000");
        assertThat(resp.injection().actualConsumeG()).isEqualByComparingTo("4.000000");
        assertThat((BigDecimal) stationSnapshot().get("odor_consumed")).isEqualByComparingTo("4.000000");
        assertThat(resp.taskId()).isNotNull();
        assertThat(countOf("linkage_task", "request_id = ? AND status = 'COMPLETED'", "L-TX")).isEqualTo(1);
        assertThat(countOf("linkage_slot", "request_id = ?", "L-TX")).isEqualTo(1);
    }

    @Test
    void onlyOneLinkageAtSameStationAndTimeIsAccepted() {
        service.regulate(req("L-FIRST", T, "180", "100"));

        assertThatThrownBy(() -> service.regulate(req("L-SECOND", T, "180", "100")))
                .isInstanceOf(TaskSlotConflictException.class);

        // 落选请求不产生任何业务数据
        assertThat(countOf("pressure_detection", "request_id = ?", "L-SECOND")).isZero();
        assertThat(countOf("flow_reading", "request_id = ?", "L-SECOND")).isZero();
        assertThat(countOf("linkage_task", "request_id = ?", "L-SECOND")).isZero();
        assertThat(countOf("linkage_slot", "station_code = ?", STATION)).isEqualTo(1);
    }

    @Test
    void differentTimesAreIndependentAndSameKeyReplays() {
        LinkageResponse first = service.regulate(req("L-DUP", T, "180", "100"));
        // 同一时刻不同 requestId 已占用；不同时刻允许
        LinkageResponse other = service.regulate(req("L-OTHER", T.plusMinutes(5), "180", "100"));
        assertThat(other.taskId()).isNotNull();

        LinkageResponse replay = service.regulate(req("L-DUP", T, "180.0", "100.00"));
        assertThat(replay.idempotentReplayed()).isTrue();
        assertThat(replay.taskId()).isEqualTo(first.taskId());
        assertThat(countOf("linkage_task", "request_id = ?", "L-DUP")).isEqualTo(1);
    }
}
