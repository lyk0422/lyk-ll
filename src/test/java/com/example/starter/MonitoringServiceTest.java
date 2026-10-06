package com.example.starter;

import com.example.starter.domain.CommandStatus;
import com.example.starter.domain.CommandType;
import com.example.starter.domain.DisposalCommand;
import com.example.starter.domain.ZoneStatus;
import com.example.starter.repo.AlarmRepository;
import com.example.starter.repo.DisposalRepository;
import com.example.starter.repo.RetestRepository;
import com.example.starter.service.MonitoringService;
import com.example.starter.service.RecoveryService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 水质监测主流程：恰等限值、越限告警、恰 3 点升级紧急并联动处置、复测恢复。
 */
class MonitoringServiceTest extends BaseIntegrationTest {

    @Autowired
    private MonitoringService monitoringService;
    @Autowired
    private RecoveryService recoveryService;
    @Autowired
    private AlarmRepository alarmRepository;
    @Autowired
    private DisposalRepository disposalRepository;
    @Autowired
    private RetestRepository retestRepository;

    @Test
    void 恰等限值判定为正常() {
        var r1 = monitoringService.ingest("ZONE-A", "req-eq-1", new BigDecimal("5.0"),
                new BigDecimal("0.50"), new BigDecimal("18.0"), T0);
        assertTrue(r1.violatedParams().isEmpty());
        assertEquals(ZoneStatus.NORMAL, r1.zoneStatus());
        assertEquals(0, r1.consecutiveViolations());

        var r2 = monitoringService.ingest("ZONE-A", "req-eq-2", new BigDecimal("5.0"),
                new BigDecimal("0.50"), new BigDecimal("28.0"), T0.plusMinutes(1));
        assertTrue(r2.violatedParams().isEmpty());
        assertEquals(0, alarmRepository.findByZone(zoneId("ZONE-A")).size());
        assertEquals("NORMAL", zoneStatus("ZONE-A"));
    }

    @Test
    void 任一参数越限即告警() {
        var result = monitoringService.ingest("ZONE-A", "req-vio-1", new BigDecimal("4.999"),
                new BigDecimal("0.50"), new BigDecimal("20.0"), T0);
        assertEquals(List.of("DO"), result.violatedParams());
        assertEquals(ZoneStatus.ALARM, result.zoneStatus());
        assertEquals(1, result.consecutiveViolations());
        assertEquals(1, alarmRepository.findByZone(zoneId("ZONE-A")).size());
        assertEquals("ALARM", zoneStatus("ZONE-A"));
    }

    @Test
    void 正常采样使连续越限计数复位() {
        monitoringService.ingest("ZONE-A", "req-rst-1", new BigDecimal("4.0"),
                new BigDecimal("0.10"), new BigDecimal("20.0"), T0);
        var normal = monitoringService.ingest("ZONE-A", "req-rst-2", new BigDecimal("6.0"),
                new BigDecimal("0.10"), new BigDecimal("20.0"), T0.plusMinutes(1));
        assertEquals(0, normal.consecutiveViolations());
        assertEquals(ZoneStatus.NORMAL, normal.zoneStatus());
        // 再次越限从 1 重新计数
        var again = monitoringService.ingest("ZONE-A", "req-rst-3", new BigDecimal("4.0"),
                new BigDecimal("0.10"), new BigDecimal("20.0"), T0.plusMinutes(2));
        assertEquals(1, again.consecutiveViolations());
    }

    @Test
    void 恰3个连续越限点升级紧急并自动下发处置() {
        long zoneId = zoneId("ZONE-A");
        monitoringService.ingest("ZONE-A", "req-esc-1", new BigDecimal("4.0"),
                new BigDecimal("0.10"), new BigDecimal("20.0"), T0);
        var second = monitoringService.ingest("ZONE-A", "req-esc-2", new BigDecimal("4.0"),
                new BigDecimal("0.10"), new BigDecimal("20.0"), T0.plusMinutes(1));
        assertEquals(ZoneStatus.ALARM, second.zoneStatus());
        assertEquals(2, second.consecutiveViolations());
        assertTrue(disposalRepository.findByZone(zoneId).isEmpty(), "第 2 点不应下发指令");

        var third = monitoringService.ingest("ZONE-A", "req-esc-3", new BigDecimal("4.0"),
                new BigDecimal("0.10"), new BigDecimal("20.0"), T0.plusMinutes(2));
        assertEquals(3, third.consecutiveViolations());
        assertEquals(ZoneStatus.DISPOSING, third.zoneStatus());

        var alarms = alarmRepository.findByZone(zoneId);
        assertEquals(3, alarms.size());
        assertEquals("ESCALATED", alarms.get(2).status().name());

        List<DisposalCommand> commands = disposalRepository.findByZone(zoneId);
        assertEquals(1, commands.size());
        DisposalCommand command = commands.get(0);
        assertEquals(CommandType.AERATION, command.commandType());
        assertEquals(CommandStatus.PENDING, command.status());
        assertEquals(T0.plusMinutes(2).plusMinutes(5), command.confirmDeadline(),
                "回执时限应为下发时刻起恰 5 分钟");
        assertEquals(alarms.get(2).id(), command.alarmId());
    }

    @Test
    void 复测合格恢复且复测记录与状态变更同事务生效() {
        long zoneId = zoneId("ZONE-A");
        for (int i = 0; i < 3; i++) {
            monitoringService.ingest("ZONE-A", "req-rec-" + i, new BigDecimal("4.0"),
                    new BigDecimal("0.10"), new BigDecimal("20.0"), T0.plusMinutes(i));
        }
        assertEquals("DISPOSING", zoneStatus("ZONE-A"));

        var result = recoveryService.recover("ZONE-A", "req-rec-ok", new BigDecimal("6.0"),
                new BigDecimal("0.10"), new BigDecimal("22.0"), T0.plusMinutes(10));
        assertTrue(result.qualified());
        assertTrue(result.recovered());
        assertEquals(ZoneStatus.NORMAL, result.zoneStatus());

        assertEquals("NORMAL", zoneStatus("ZONE-A"));
        assertEquals(1, retestRepository.countByZone(zoneId));
        assertTrue(alarmRepository.findUnresolved(zoneId).isEmpty(), "告警应全部解除");
        assertTrue(disposalRepository.findActiveByZone(zoneId).isEmpty(), "指令应全部完成");
    }

    @Test
    void 复测不合格仅留记录不恢复() {
        long zoneId = zoneId("ZONE-A");
        for (int i = 0; i < 3; i++) {
            monitoringService.ingest("ZONE-A", "req-ng-" + i, new BigDecimal("4.0"),
                    new BigDecimal("0.10"), new BigDecimal("20.0"), T0.plusMinutes(i));
        }
        var result = recoveryService.recover("ZONE-A", "req-ng-retest", new BigDecimal("4.5"),
                new BigDecimal("0.10"), new BigDecimal("22.0"), T0.plusMinutes(10));
        assertFalse(result.qualified());
        assertFalse(result.recovered());
        assertEquals("DISPOSING", zoneStatus("ZONE-A"));
        assertEquals(1, retestRepository.countByZone(zoneId));
        assertEquals(3, alarmRepository.findUnresolved(zoneId).size());
    }

    @Test
    void 复测恰等限值视为合格() {
        for (int i = 0; i < 3; i++) {
            monitoringService.ingest("ZONE-A", "req-edge-" + i, new BigDecimal("4.0"),
                    new BigDecimal("0.10"), new BigDecimal("20.0"), T0.plusMinutes(i));
        }
        var result = recoveryService.recover("ZONE-A", "req-edge-retest", new BigDecimal("5.0"),
                new BigDecimal("0.50"), new BigDecimal("28.0"), T0.plusMinutes(10));
        assertTrue(result.qualified());
        assertTrue(result.recovered());
        assertEquals("NORMAL", zoneStatus("ZONE-A"));
    }
}
