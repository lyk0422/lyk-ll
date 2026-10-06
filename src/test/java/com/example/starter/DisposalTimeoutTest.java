package com.example.starter;

import com.example.starter.domain.CommandStatus;
import com.example.starter.domain.CommandType;
import com.example.starter.domain.DisposalCommand;
import com.example.starter.repo.DisposalRepository;
import com.example.starter.service.DisposalService;
import com.example.starter.service.MaintenanceService;
import com.example.starter.service.MonitoringService;
import com.example.starter.web.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 处置回执时限：5 分钟内确认有效，恰满 5 分钟未确认升级。
 */
class DisposalTimeoutTest extends BaseIntegrationTest {

    @Autowired
    private MonitoringService monitoringService;
    @Autowired
    private DisposalService disposalService;
    @Autowired
    private MaintenanceService maintenanceService;
    @Autowired
    private DisposalRepository disposalRepository;

    /** 指令下发时刻（第 3 个越限采样点） */
    private LocalDateTime dispatchedAt;

    @BeforeEach
    void driveToDisposing() {
        dispatchedAt = T0.plusMinutes(2);
        for (int i = 0; i < 3; i++) {
            monitoringService.ingest("ZONE-A", "req-dt-" + i, new BigDecimal("4.0"),
                    new BigDecimal("0.10"), new BigDecimal("20.0"), T0.plusMinutes(i));
        }
    }

    private DisposalCommand firstCommand() {
        return disposalRepository.findByZone(zoneId("ZONE-A")).get(0);
    }

    @Test
    void 回执在5分钟内确认成功() {
        DisposalCommand command = firstCommand();
        DisposalCommand confirmed = disposalService.confirmReceipt(command.id(),
                dispatchedAt.plusMinutes(4).plusSeconds(59));
        assertEquals(CommandStatus.CONFIRMED, confirmed.status());
        assertEquals(dispatchedAt.plusMinutes(4).plusSeconds(59), confirmed.confirmedAt());
    }

    @Test
    void 恰满5分钟未确认即升级() {
        DisposalCommand command = firstCommand();
        // 时限前 1 秒不升级
        var early = maintenanceService.tick(dispatchedAt.plusMinutes(5).minusSeconds(1));
        assertEquals(0, early.escalatedCommands());
        assertEquals(CommandStatus.PENDING, disposalRepository.findById(command.id()).orElseThrow().status());

        // 恰满 5 分钟升级
        var tick = maintenanceService.tick(dispatchedAt.plusMinutes(5));
        assertEquals(1, tick.escalatedCommands());

        List<DisposalCommand> commands = disposalRepository.findByZone(zoneId("ZONE-A"));
        assertEquals(2, commands.size());
        assertEquals(CommandStatus.ESCALATED, commands.get(0).status());
        assertEquals(dispatchedAt.plusMinutes(5), commands.get(0).escalatedAt());
        assertEquals(CommandType.WATER_CHANGE, commands.get(1).commandType());
        assertEquals(CommandStatus.PENDING, commands.get(1).status());
        assertEquals(dispatchedAt.plusMinutes(10), commands.get(1).confirmDeadline());
    }

    @Test
    void 恰满5分钟到达的回执被拒绝并升级() {
        DisposalCommand command = firstCommand();
        BusinessException e = assertThrows(BusinessException.class,
                () -> disposalService.confirmReceipt(command.id(), dispatchedAt.plusMinutes(5)));
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, e.status());
        assertEquals(CommandStatus.ESCALATED,
                disposalRepository.findById(command.id()).orElseThrow().status());
        assertTrue(disposalRepository.findByZone(zoneId("ZONE-A")).size() > 1,
                "超时后应下发升级指令");
    }

    @Test
    void 换水指令再次超时升级为停展隔离() {
        maintenanceService.tick(dispatchedAt.plusMinutes(5));
        var secondTick = maintenanceService.tick(dispatchedAt.plusMinutes(10));
        assertEquals(1, secondTick.escalatedCommands());
        List<DisposalCommand> commands = disposalRepository.findByZone(zoneId("ZONE-A"));
        assertEquals(3, commands.size());
        assertEquals(CommandType.CLOSURE_ISOLATION, commands.get(2).commandType());
        assertEquals(CommandStatus.PENDING, commands.get(2).status());
    }

    @Test
    void 已确认指令不会因超时被升级() {
        DisposalCommand command = firstCommand();
        disposalService.confirmReceipt(command.id(), dispatchedAt.plusMinutes(1));
        var tick = maintenanceService.tick(dispatchedAt.plusMinutes(30));
        assertEquals(0, tick.escalatedCommands());
        assertEquals(CommandStatus.CONFIRMED,
                disposalRepository.findById(command.id()).orElseThrow().status());
    }
}
