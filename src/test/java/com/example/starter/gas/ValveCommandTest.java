package com.example.starter.gas;

import com.example.starter.gas.service.GasStationService;
import com.example.starter.gas.web.dto.DetectionRequest;
import com.example.starter.gas.web.dto.ValveCommandRequest;
import com.example.starter.gas.web.dto.ValveCommandResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 锁阀/复开双人确认：单人不生效、双人异人才生效、复开与告警解除同事务。
 */
@Import(FixedClockConfig.class)
class ValveCommandTest extends AbstractGasIntegrationTest {

    private static final LocalDateTime T = LocalDateTime.of(2026, 9, 26, 11, 0, 0);

    @Autowired
    private GasStationService service;

    private ValveCommandRequest valve(String requestId, String action, String op1, String op2) {
        return new ValveCommandRequest(requestId, STATION, action, op1, op2, T);
    }

    private void triggerAlarmLock() {
        service.detect(new DetectionRequest("A-1", STATION, T.minusMinutes(10),
                new BigDecimal("25"), new BigDecimal("100"), null));
        service.detect(new DetectionRequest("A-2", STATION, T.minusMinutes(9),
                new BigDecimal("25"), new BigDecimal("100"), null));
        assertThat(stationSnapshot().get("valve_state")).isEqualTo("LOCKED");
    }

    @Test
    void singleOperatorLockDoesNotTakeEffect() {
        ValveCommandResponse resp = service.valveCommand(valve("V-1", "LOCK", "alice", null));
        assertThat(resp.effective()).isFalse();
        assertThat(stationSnapshot().get("valve_state")).isEqualTo("OPEN");
    }

    @Test
    void sameOperatorTwiceIsNotDualConfirmation() {
        ValveCommandResponse resp = service.valveCommand(valve("V-1", "LOCK", "alice", "alice"));
        assertThat(resp.effective()).isFalse();
        assertThat(stationSnapshot().get("valve_state")).isEqualTo("OPEN");
    }

    @Test
    void twoDifferentOperatorsLockTakesEffect() {
        ValveCommandResponse resp = service.valveCommand(valve("V-1", "LOCK", "alice", "bob"));
        assertThat(resp.effective()).isTrue();
        assertThat(resp.valveState()).isEqualTo("LOCKED");
        assertThat(countOf("valve_command", "request_id = ? AND effective = TRUE", "V-1")).isEqualTo(1);
    }

    @Test
    void reopenRequiresTwoOperatorsAndClearsAlarmInSameTransaction() {
        triggerAlarmLock();

        // 单人复开不生效，告警仍在
        ValveCommandResponse single = service.valveCommand(valve("V-SOLO", "REOPEN", "alice", null));
        assertThat(single.effective()).isFalse();
        var stillLocked = stationSnapshot();
        assertThat(stillLocked.get("valve_state")).isEqualTo("LOCKED");
        assertThat(stillLocked.get("alarm_active")).isEqualTo(true);

        // 双人复开：阀门打开、告警解除、连续计数清零，在同一事务完成
        ValveCommandResponse dual = service.valveCommand(valve("V-DUAL", "REOPEN", "alice", "bob"));
        assertThat(dual.effective()).isTrue();
        assertThat(dual.valveState()).isEqualTo("OPEN");
        assertThat(dual.alarmActive()).isFalse();
        var snap = stationSnapshot();
        assertThat(snap.get("valve_state")).isEqualTo("OPEN");
        assertThat(snap.get("alarm_active")).isEqualTo(false);
        assertThat(snap.get("violation_count")).isEqualTo(0);
        assertThat(countOf("station_event", "event_kind = 'VALVE_REOPENED'")).isEqualTo(1);
    }

    @Test
    void valveCommandIsIdempotent() {
        ValveCommandResponse first = service.valveCommand(valve("V-IDEM", "LOCK", "alice", "bob"));
        assertThat(first.effective()).isTrue();
        ValveCommandResponse replay = service.valveCommand(valve("V-IDEM", "LOCK", "alice", "bob"));
        assertThat(replay.idempotentReplayed()).isTrue();
        assertThat(replay.effective()).isTrue();
        assertThat(countOf("valve_command", "request_id = ?", "V-IDEM")).isEqualTo(1);
    }
}
