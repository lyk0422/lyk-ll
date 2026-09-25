package com.example.starter.waterlogging;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.example.starter.waterlogging.api.LevelReadingRequest;
import com.example.starter.waterlogging.api.LossContactCheckRequest;
import com.example.starter.waterlogging.api.LossContactResult;
import com.example.starter.waterlogging.service.WaterLoggingService;
import com.example.starter.waterlogging.web.IdempotentResponse;

/**
 * 失联派单规则：恰满 30 分钟无数据判定失联并派单，短一秒不判定；
 * 同站开放派单唯一；检查接口以 requestId 幂等。
 */
class LossContactRuleTest extends AbstractH2IntegrationTest {

    private static final LocalDateTime T0 = LocalDateTime.of(2026, 9, 26, 8, 0, 0);

    @Autowired
    private WaterLoggingService service;

    private LossContactCheckRequest check(String requestId, String station, LocalDateTime time) {
        return new LossContactCheckRequest(requestId, station, time);
    }

    @Test
    void exactlyThirtyMinutesDeclaresLossContactButOneSecondBeforeDoesNot() {
        // 08:00 有一条数据。
        service.reportReading(new LevelReadingRequest(
                "READ-LOSS-1", "DW-01", T0, new java.math.BigDecimal("4.00")));

        // 08:29:59：尚不满 30 分钟，不派单。
        var before = service.checkLossContact(check(
                "LOSS-CHECK-1", "DW-01", T0.plusMinutes(29).plusSeconds(59)));
        assertThat(before.data().lostContact()).isFalse();
        assertThat(before.data().orderId()).isNull();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM loss_contact_order", Integer.class)).isZero();

        // 08:30:00：恰满 30 分钟，判定失联并派单。
        var atThreshold = service.checkLossContact(check(
                "LOSS-CHECK-2", "DW-01", T0.plusMinutes(30)));
        assertThat(atThreshold.data().lostContact()).isTrue();
        assertThat(atThreshold.data().lastReadingTime()).isEqualTo(T0);
        assertThat(atThreshold.data().declaredAt()).isEqualTo(T0.plusMinutes(30));
        Long orderId = atThreshold.data().orderId();
        assertThat(orderId).isNotNull();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM loss_contact_order WHERE id = ?", String.class, orderId))
                .isEqualTo("OPEN");

        // 同 requestId 重放：返回首次派单结果。
        var replay = service.checkLossContact(check(
                "LOSS-CHECK-2", "DW-01", T0.plusMinutes(30)));
        assertThat(replay.replay()).isTrue();
        // 新 requestId 再次检查：站点已有开放派单，只返回既有单，不重复派单。
        var again = service.checkLossContact(check(
                "LOSS-CHECK-3", "DW-01", T0.plusMinutes(35)));
        assertThat(again.data().lostContact()).isTrue();
        assertThat(again.data().orderId()).isEqualTo(orderId);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM loss_contact_order WHERE station_code = 'DW-01'",
                Integer.class)).isEqualTo(1);
    }

    @Test
    void stationWithoutAnyReadingIsDeclaredLostAndOrderLedgerRecomputable() {
        // 从无数据：检查即判定失联，last_reading_time 为空。
        var lost = service.checkLossContact(check("LOSS-CHECK-EMPTY", "XH-01", T0));
        assertThat(lost.data().lostContact()).isTrue();
        assertThat(lost.data().lastReadingTime()).isNull();

        var orders = jdbcTemplate.queryForList("""
                SELECT station_code, last_reading_time, status FROM loss_contact_order
                WHERE station_code = 'XH-01'
                """);
        assertThat(orders).hasSize(1);
        assertThat(orders.get(0).get("STATUS")).isEqualTo("OPEN");
        assertThat(orders.get(0).get("LAST_READING_TIME")).isNull();
    }
}
