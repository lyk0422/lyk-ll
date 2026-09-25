package com.example.starter.service;

import com.example.starter.domain.BaseStation;
import com.example.starter.domain.Generator;
import com.example.starter.testsupport.AbstractIntegrationTest;
import com.example.starter.testsupport.Fixtures;
import com.example.starter.web.UnprocessableException;
import com.example.starter.web.dto.AlertRequest;
import com.example.starter.web.dto.ClosureRequest;
import com.example.starter.web.dto.ClosureResponse;
import com.example.starter.web.dto.ConfirmationRequest;
import com.example.starter.web.dto.ConfirmationResponse;
import com.example.starter.web.dto.DispatchRequest;
import com.example.starter.web.dto.DispatchResponse;
import com.example.starter.web.dto.ReadingRequest;
import com.example.starter.web.dto.ReassignmentRequest;
import com.example.starter.web.dto.ReassignmentResponse;
import com.example.starter.web.dto.RestorationRequest;
import com.example.starter.web.dto.RestorationResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 油机生命周期：30 分钟确认时限（恰满判超时）、超时改派原子性、
 * 市电恢复解除并释放油机、结案终态与迟到确认。
 */
class BackupPowerLifecycleTest extends AbstractIntegrationTest {

    private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");

    @Autowired
    private BackupPowerService service;
    @Autowired
    private Fixtures fixtures;

    private DispatchResponse dispatchLowCapacity(String stationCode, String genCode) {
        fixtures.generator(genCode);
        fixtures.station(stationCode, "3000", "2000");
        service.handleAlert(new AlertRequest("REQ-A-" + stationCode, stationCode, T0));
        service.handleReading(new ReadingRequest("REQ-R-" + stationCode, stationCode, T0,
                new BigDecimal("1000")));
        return service.handleDispatch(
                new DispatchRequest("REQ-D-" + stationCode, stationCode, T0,
                        genCode == null ? null : genCode));
    }

    @Test
    void confirmation_before_30_minutes_succeeds() {
        dispatchLowCapacity("BS-1", "GEN-1");
        clock.advanceSeconds(29 * 60 + 59);

        ConfirmationResponse response = service.handleConfirmation(
                new ConfirmationRequest("REQ-C1", "REQ-D-BS-1"));

        assertThat(response.result()).isEqualTo(ConfirmationResponse.CONFIRMED);
        assertThat(response.order().status()).isEqualTo("GENERATING");
        assertThat(response.order().confirmedAt()).isEqualTo(clock.now());
        assertThat(generatorStatus("GEN-1")).isEqualTo("GENERATING");
    }

    @Test
    void confirmation_at_exactly_30_minutes_times_out_and_releases_generator() {
        dispatchLowCapacity("BS-1", "GEN-1");
        clock.advanceSeconds(30 * 60);

        ConfirmationResponse response = service.handleConfirmation(
                new ConfirmationRequest("REQ-C1", "REQ-D-BS-1"));

        assertThat(response.result()).isEqualTo(ConfirmationResponse.TIMEOUT_ABNORMAL);
        assertThat(response.order().status()).isEqualTo("GENERATION_ABNORMAL");
        // 原油机同事务释放，可再被其他基站使用。
        assertThat(generatorStatus("GEN-1")).isEqualTo("IDLE");
        assertThat(count("generator_active_order")).isZero();
    }

    @Test
    void reassignment_after_timeout_atomically_swaps_generator() {
        dispatchLowCapacity("BS-1", "GEN-1");
        fixtures.generator("GEN-2");
        clock.advanceSeconds(30 * 60);
        service.handleConfirmation(new ConfirmationRequest("REQ-C1", "REQ-D-BS-1"));

        ReassignmentResponse response = service.handleReassignment(
                new ReassignmentRequest("REQ-X1", "REQ-D-BS-1", "GEN-2"));

        assertThat(response.oldGenCode()).isEqualTo("GEN-1");
        assertThat(response.newGenCode()).isEqualTo("GEN-2");
        assertThat(response.order().status()).isEqualTo("DISPATCHED");
        assertThat(response.order().genCode()).isEqualTo("GEN-2");
        // 同一调度单，不会两台油机同时在途服务同站。
        assertThat(count("dispatch_order")).isEqualTo(1);
        assertThat(count("generator_active_order")).isEqualTo(1);
        assertThat(generatorStatus("GEN-1")).isEqualTo("IDLE");
        assertThat(generatorStatus("GEN-2")).isEqualTo("DISPATCHED");
        assertThat(count("dispatch_reassignment")).isEqualTo(1);
        // 改派后重新计时 30 分钟。
        assertThat(response.order().dispatchDeadline())
                .isEqualTo(clock.now().plusSeconds(30 * 60));

        // 新油机在新窗口内确认成功。
        clock.advanceSeconds(10 * 60);
        ConfirmationResponse confirm = service.handleConfirmation(
                new ConfirmationRequest("REQ-C2", "REQ-D-BS-1"));
        assertThat(confirm.result()).isEqualTo(ConfirmationResponse.CONFIRMED);
        assertThat(confirm.order().status()).isEqualTo("GENERATING");
        assertThat(generatorStatus("GEN-2")).isEqualTo("GENERATING");
    }

    @Test
    void reassignment_before_timeout_is_rejected() {
        dispatchLowCapacity("BS-1", "GEN-1");
        fixtures.generator("GEN-2");
        clock.advanceSeconds(10 * 60);

        assertThatThrownBy(() -> service.handleReassignment(
                new ReassignmentRequest("REQ-X1", "REQ-D-BS-1", "GEN-2")))
                .isInstanceOf(UnprocessableException.class)
                .hasMessageContaining("超时");
        assertThat(generatorStatus("GEN-1")).isEqualTo("DISPATCHED");
        assertThat(generatorStatus("GEN-2")).isEqualTo("IDLE");
    }

    @Test
    void restoration_recharges_battery_releases_order_and_generator() {
        dispatchLowCapacity("BS-1", "GEN-1");
        BaseStation station = service.getStation("BS-1");
        clock.advanceSeconds(5 * 60);
        service.handleConfirmation(new ConfirmationRequest("REQ-C1", "REQ-D-BS-1"));

        Instant restoredAt = clock.now().plusSeconds(3600);
        RestorationResponse response = service.handleRestoration(
                new RestorationRequest("REQ-S1", "BS-1", restoredAt));

        assertThat(response.releasedOrder()).isNotNull();
        assertThat(response.releasedOrder().status()).isEqualTo("RELEASED");
        assertThat(response.batteryFullWh()).isEqualByComparingTo("3000");
        BigDecimal remaining = jdbc.queryForObject(
                "SELECT current_remaining_wh FROM base_station WHERE id = ?",
                BigDecimal.class, station.id());
        assertThat(remaining).isEqualByComparingTo("3000");
        assertThat(generatorStatus("GEN-1")).isEqualTo("IDLE");
        assertThat(count("station_active_order")).isZero();
        assertThat(count("generator_active_order")).isZero();
        assertThat(eventStatus(response.eventId())).isEqualTo("RESTORED");
    }

    @Test
    void restoration_without_active_order_only_ends_outage_and_recharges() {
        BaseStation station = fixtures.station("BS-1", "6000", "2000");
        service.handleAlert(new AlertRequest("REQ-A1", "BS-1", T0));
        service.handleReading(new ReadingRequest("REQ-R1", "BS-1", T0, new BigDecimal("6000")));

        RestorationResponse response = service.handleRestoration(
                new RestorationRequest("REQ-S1", "BS-1", T0.plusSeconds(1800)));

        assertThat(response.releasedOrder()).isNull();
        assertThat(count("dispatch_order")).isZero();
        BigDecimal remaining = jdbc.queryForObject(
                "SELECT current_remaining_wh FROM base_station WHERE id = ?",
                BigDecimal.class, station.id());
        assertThat(remaining).isEqualByComparingTo("6000");
    }

    @Test
    void closure_is_terminal_and_repeated_closure_is_idempotent() {
        dispatchLowCapacity("BS-1", "GEN-1");
        clock.advanceSeconds(5 * 60);
        service.handleConfirmation(new ConfirmationRequest("REQ-C1", "REQ-D-BS-1"));

        ClosureResponse first = service.handleClosure(
                new ClosureRequest("REQ-K1", "REQ-D-BS-1"));
        ClosureResponse repeated = service.handleClosure(
                new ClosureRequest("REQ-K2", "REQ-D-BS-1"));

        assertThat(first.repeated()).isFalse();
        assertThat(first.order().status()).isEqualTo("CLOSED");
        assertThat(repeated.repeated()).isTrue();
        assertThat(repeated.order().status()).isEqualTo("CLOSED");
        assertThat(repeated.order().closedAt()).isEqualTo(first.order().closedAt());
        assertThat(generatorStatus("GEN-1")).isEqualTo("IDLE");
        assertThat(count("station_active_order")).isZero();
    }

    @Test
    void late_confirmation_after_closure_is_recorded_but_does_not_change_outcome() {
        dispatchLowCapacity("BS-1", "GEN-1");
        clock.advanceSeconds(5 * 60);
        service.handleConfirmation(new ConfirmationRequest("REQ-C1", "REQ-D-BS-1"));
        service.handleClosure(new ClosureRequest("REQ-K1", "REQ-D-BS-1"));

        ConfirmationResponse late = service.handleConfirmation(
                new ConfirmationRequest("REQ-C3", "REQ-D-BS-1"));

        assertThat(late.result()).isEqualTo(ConfirmationResponse.LATE_RECORDED);
        assertThat(late.order().status()).isEqualTo("CLOSED");
        assertThat(count("order_event")).isEqualTo(4); // 派单、确认、结案、迟到确认
        assertThat(generatorStatus("GEN-1")).isEqualTo("IDLE");
    }

    @Test
    void late_confirmation_after_release_is_recorded_but_order_stays_released() {
        dispatchLowCapacity("BS-1", "GEN-1");
        service.handleRestoration(new RestorationRequest("REQ-S1", "BS-1", T0.plusSeconds(600)));

        ConfirmationResponse late = service.handleConfirmation(
                new ConfirmationRequest("REQ-C2", "REQ-D-BS-1"));

        assertThat(late.result()).isEqualTo(ConfirmationResponse.LATE_RECORDED);
        assertThat(late.order().status()).isEqualTo("RELEASED");
    }

    private String generatorStatus(String genCode) {
        return jdbc.queryForObject("SELECT status FROM generator WHERE gen_code = ?",
                String.class, genCode);
    }

    private String eventStatus(long eventId) {
        return jdbc.queryForObject("SELECT status FROM outage_event WHERE id = ?",
                String.class, eventId);
    }
}
