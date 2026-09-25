package com.example.starter.pump.business;

import com.example.starter.pump.testsupport.AbstractIntegrationTest;
import com.example.starter.pump.testsupport.GateAtomicityProbe;
import com.example.starter.pump.service.PumpdownFacade;
import com.example.starter.pump.web.dto.WarningRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 闸门联动原子性（H2 真实约束 + 事务回滚）：启泵任务与闸门开度在同一事务，
 * 闸门未到位（违反开度 CHECK）时启泵任务一并回滚，不存在“开泵而闸门未到位”。
 */
class GateAtomicityTest extends AbstractIntegrationTest {

    private static final LocalDateTime T0 = LocalDateTime.of(2026, 9, 26, 8, 0);

    @Autowired
    private PumpdownFacade facade;

    @Autowired
    private GateAtomicityProbe probe;

    @Test
    void gateCheckFailure_rollsBackPumpStartInSameTransaction() {
        Long id = facade.handleWarning(new WarningRequest("w-gate", "ZN", "ORANGE", T0)).pumpdownId();

        assertThatThrownBy(() -> probe.startPumpThenFailGate(999L, id, "PS-01", T0))
                .isInstanceOf(DataIntegrityViolationException.class);

        // 同一事务回滚：启泵任务与闸门日志均不得残留
        assertThat(count("pump_task")).isZero();
        assertThat(count("gate_opening_log")).isZero();
    }

    @Test
    void normalStart_commitsPumpAndGateAtomically() {
        Long id = facade.handleWarning(new WarningRequest("w-gate-ok", "ZN", "ORANGE", T0)).pumpdownId();
        facade.handlePumpCommand(new com.example.starter.pump.web.dto.PumpCommandRequest(
                "t-g", id, "PS-01", "START", T0, new java.math.BigDecimal("80.00")));

        assertThat(count("pump_task")).isEqualTo(1);
        assertThat(count("gate_opening_log")).isEqualTo(1);
        Long opening = jdbc.queryForObject(
                "SELECT opening FROM gate_opening_log", Long.class);
        assertThat(opening).isEqualTo(80L);
    }
}
