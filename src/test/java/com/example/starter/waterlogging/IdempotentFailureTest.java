package com.example.starter.waterlogging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;

import com.example.starter.waterlogging.api.WarningStartRequest;
import com.example.starter.waterlogging.exception.BusinessRuleException;
import com.example.starter.waterlogging.exception.ConflictException;
import com.example.starter.waterlogging.service.WaterLoggingService;

/**
 * 幂等失败语义：确定性业务失败也按 requestId 留存——
 * 同键同参重放相同错误，同键异参 409；联动启机中途失败时保存点回滚全部业务副作用，
 * 不得出现一台已开泵而整单失败的半截状态。
 */
class IdempotentFailureTest extends AbstractH2IntegrationTest {

    private static final LocalDateTime T0 = LocalDateTime.of(2026, 9, 26, 10, 0, 0);

    @Autowired
    private WaterLoggingService service;

    private WarningStartRequest warning(String requestId, String level, BigDecimal target,
                                        BigDecimal gate) {
        return new WarningStartRequest(requestId, level, "DONGWAN", "东湾河", target, T0, gate);
    }

    @Test
    void businessFailureIsReplayedForSameParamsButConflictsForDifferentParams() {
        // 黄色预警：确定性 422。
        assertThatThrownBy(() -> service.startOnWarning(
                warning("FAIL-IDEMP-1", "YELLOW", new BigDecimal("3.00"), new BigDecimal("60.00"))))
                .isInstanceOf(BusinessRuleException.class)
                .satisfies(ex -> assertThat(((BusinessRuleException) ex).getStatus())
                        .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY));

        // 同键同参重放：仍是同样的 422（错误结果幂等）。
        assertThatThrownBy(() -> service.startOnWarning(
                warning("FAIL-IDEMP-1", "YELLOW", new BigDecimal("3.00"), new BigDecimal("60.00"))))
                .isInstanceOf(BusinessRuleException.class);
        // 同键异参（改成橙色）：409 指纹冲突。
        assertThatThrownBy(() -> service.startOnWarning(
                warning("FAIL-IDEMP-1", "ORANGE", new BigDecimal("3.00"), new BigDecimal("60.00"))))
                .isInstanceOf(ConflictException.class)
                .satisfies(ex -> assertThat(((ConflictException) ex).getStatus())
                        .isEqualTo(HttpStatus.CONFLICT));

        // 失败全程无业务副作用：无预降单、无启停台账、泵站全停。
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM pre_drain_plan", Integer.class)).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM pump_command", Integer.class)).isZero();
        // 失败结果台账恰好一条，状态 422。
        assertThat(jdbcTemplate.queryForObject("""
                SELECT response_status FROM idempotent_request
                WHERE request_id = 'FAIL-IDEMP-1'
                """, Integer.class)).isEqualTo(422);
    }

    @Test
    void linkedStartupRollsBackFullyWhenSecondStationAlreadyRunning() {
        // 预置 DW-02 已在运行（合法状态：运行且闸门开启），DW-01 停机。
        jdbcTemplate.update("UPDATE pump_station SET run_status = 'RUNNING', gate_opening = 60 "
                + "WHERE station_code = 'DW-02'");

        // 橙色预警联动：DW-01 先启机（半截副作用），DW-02 已运行 → 整单 422。
        assertThatThrownBy(() -> service.startOnWarning(
                warning("FAIL-IDEMP-2", "ORANGE", new BigDecimal("3.00"), new BigDecimal("60.00"))))
                .isInstanceOf(BusinessRuleException.class);

        // 保存点回滚：DW-01 不得保持开泵；无预降单、无序列锁、无启停台账。
        assertThat(jdbcTemplate.queryForObject(
                "SELECT run_status FROM pump_station WHERE station_code = 'DW-01'", String.class))
                .isEqualTo("STOPPED");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT gate_opening FROM pump_station WHERE station_code = 'DW-01'", BigDecimal.class))
                .isEqualByComparingTo("0");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM pre_drain_plan", Integer.class)).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM pump_command", Integer.class)).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM zone_sequence_lock", Integer.class)).isZero();
        // DW-02 预置状态不被污染。
        assertThat(jdbcTemplate.queryForObject(
                "SELECT run_status FROM pump_station WHERE station_code = 'DW-02'", String.class))
                .isEqualTo("RUNNING");

        // 同键同参再试仍是相同失败，同键异参 409。
        assertThatThrownBy(() -> service.startOnWarning(
                warning("FAIL-IDEMP-2", "ORANGE", new BigDecimal("3.00"), new BigDecimal("60.00"))))
                .isInstanceOf(BusinessRuleException.class);
        assertThatThrownBy(() -> service.startOnWarning(
                warning("FAIL-IDEMP-2", "RED", new BigDecimal("3.00"), new BigDecimal("60.00"))))
                .isInstanceOf(ConflictException.class);
    }
}
