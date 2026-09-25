package com.example.starter.waterlogging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDateTime;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;

import com.example.starter.waterlogging.api.PumpCommandRequest;
import com.example.starter.waterlogging.api.WarningStartRequest;
import com.example.starter.waterlogging.domain.PumpAction;
import com.example.starter.waterlogging.exception.BusinessRuleException;
import com.example.starter.waterlogging.exception.ConflictException;
import com.example.starter.waterlogging.service.WaterLoggingService;

/**
 * 泵站启停规则：启机恰 10 分钟间隔合法、短一秒拒绝；同站同时刻唯一受理；
 * 启泵必开闸、停机必关闸；无活跃预降序列拒令。
 */
class PumpRuleTest extends AbstractH2IntegrationTest {

    private static final LocalDateTime T0 = LocalDateTime.of(2026, 9, 26, 8, 0, 0);
    private static final BigDecimal GATE = new BigDecimal("55.00");

    @Autowired
    private WaterLoggingService service;

    private WarningStartRequest warning(String requestId, String zone, LocalDateTime time) {
        return new WarningStartRequest(requestId, "ORANGE", zone, "东湾河",
                new BigDecimal("3.00"), time, new BigDecimal("60.00"));
    }

    private PumpCommandRequest start(String requestId, String station, LocalDateTime time) {
        return new PumpCommandRequest(requestId, station, PumpAction.START, time, GATE);
    }

    private PumpCommandRequest stop(String requestId, String station, LocalDateTime time) {
        return new PumpCommandRequest(requestId, station, PumpAction.STOP, time, BigDecimal.ZERO);
    }

    @Test
    void startIntervalExactlyTenMinutesIsLegalButOneSecondLessRejected() {
        service.startOnWarning(warning("WARN-PUMP-1", "DONGWAN", T0));

        // DW-01 联动启机于 08:00:00；08:05:00 停机。
        service.commandPump(stop("CMD-STOP-1", "DW-01", T0.plusMinutes(5)));

        // 再次启机距上次启机（08:00:00）超过 10 分钟，合法。
        service.commandPump(start("CMD-START-1", "DW-01", T0.plusMinutes(15)));
        service.commandPump(stop("CMD-STOP-2", "DW-01", T0.plusMinutes(20)));

        // 距上次启机 08:15:00 恰 10 分钟（08:25:00）：合法防水锤边界。
        service.commandPump(start("CMD-START-2", "DW-01", T0.plusMinutes(25)));
        assertThat(queryStationStatus("DW-01")).isEqualTo(PumpAction.RUNNING);

        service.commandPump(stop("CMD-STOP-3", "DW-01", T0.plusMinutes(26)));

        // 距上次启机仅 9 分 59 秒（08:34:59）：拒绝。
        LocalDateTime tooSoon = T0.plusMinutes(34).plusSeconds(59);
        assertThatThrownBy(() -> service.commandPump(start("CMD-START-3", "DW-01", tooSoon)))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("10 分钟");
        // 拒绝后泵站状态不变：仍停机、闸门全关。
        assertThat(queryStationStatus("DW-01")).isEqualTo(PumpAction.STOPPED);
        assertThat(queryStationGate("DW-01")).isEqualByComparingTo("0");
    }

    @Test
    void sameStationSameTimeAcceptsOnlyOneCommand() {
        service.startOnWarning(warning("WARN-PUMP-2", "DONGWAN", T0));

        LocalDateTime stopTime = T0.plusMinutes(8);
        service.commandPump(stop("CMD-UNIQUE-1", "DW-02", stopTime));

        // 不同 requestId、同站同时刻的第二条指令：409，且台账只有一条。
        assertThatThrownBy(() -> service.commandPump(stop("CMD-UNIQUE-2", "DW-02", stopTime)))
                .isInstanceOf(ConflictException.class)
                .satisfies(ex -> assertThat(((ConflictException) ex).getStatus())
                        .isEqualTo(HttpStatus.CONFLICT));
        Integer count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM pump_command
                WHERE station_code = 'DW-02' AND command_time = ?
                """, Integer.class, Timestamp.valueOf(stopTime));
        assertThat(count).isEqualTo(1);
    }

    @Test
    void startMustOpenGateAndStopMustCloseGate() {
        service.startOnWarning(warning("WARN-PUMP-3", "DONGWAN", T0));

        // 先把 DW-01 停机，才能再发启机指令。
        service.commandPump(stop("CMD-GATE-STOP", "DW-01", T0.plusMinutes(2)));
        // 启机开度为 0：拒绝。
        assertThatThrownBy(() -> service.commandPump(new PumpCommandRequest(
                "CMD-GATE-START-0", "DW-01", PumpAction.START, T0.plusMinutes(12), BigDecimal.ZERO)))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("闸门");

        // 停机时闸门未全关：拒绝，DW-02 保持运行。
        assertThatThrownBy(() -> service.commandPump(new PumpCommandRequest(
                "CMD-GATE-STOP-OPEN", "DW-02", PumpAction.STOP, T0.plusMinutes(3),
                new BigDecimal("30.00"))))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("全关");
        assertThat(queryStationStatus("DW-02")).isEqualTo(PumpAction.RUNNING);
        assertThat(queryStationGate("DW-02")).isEqualByComparingTo("60.00");
    }

    @Test
    void commandWithoutActivePlanIsRejected() {
        // XIHU 分区从未启动预降，任何启停指令都不受理。
        assertThatThrownBy(() -> service.commandPump(start("CMD-NO-PLAN", "XH-01", T0)))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("预降序列");
    }

    @Test
    void databaseCheckConstraintsEnforcePumpGateAtomicity() {
        service.startOnWarning(warning("WARN-PUMP-4", "DONGWAN", T0));

        // 直接绕过服务写入“启机但闸门未到位”记录：H2 检查约束必须拒绝，
        // 证明“不得开泵而闸门未到位”由数据库边界兜底。
        assertThatThrownBy(() -> jdbcTemplate.update("""
                        INSERT INTO pump_command
                            (plan_id, station_code, action, sequence_no, command_time,
                             gate_opening, gate_adjusted, request_id, created_at)
                        VALUES (NULL, 'DW-01', 'START', 99, CURRENT_TIMESTAMP,
                                0, FALSE, 'BROKEN', CURRENT_TIMESTAMP)
                        """))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_pump_command_start_gate");

        // 运行机组闸门开度必须大于 0（pump_station 检查约束）。
        assertThatThrownBy(() -> jdbcTemplate.update("""
                        UPDATE pump_station SET run_status = 'RUNNING', gate_opening = 0
                        WHERE station_code = 'DW-01'
                        """))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_pump_station_gate");
    }

    private String queryStationStatus(String stationCode) {
        return jdbcTemplate.queryForObject(
                "SELECT run_status FROM pump_station WHERE station_code = ?",
                String.class, stationCode);
    }

    private BigDecimal queryStationGate(String stationCode) {
        return jdbcTemplate.queryForObject(
                "SELECT gate_opening FROM pump_station WHERE station_code = ?",
                BigDecimal.class, stationCode);
    }
}
