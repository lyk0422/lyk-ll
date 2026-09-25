package com.example.starter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;

import com.example.starter.common.ApiException;
import com.example.starter.elevator.Elevator;
import com.example.starter.elevator.ElevatorService;
import com.example.starter.maintenance.MaintenanceRepository;
import com.example.starter.maintenance.MaintenanceService;

/**
 * 维保周期测试：恰第 15 日完成合规，逾期锁定电梯并列重点监察，登记幂等。
 */
class MaintenanceServiceTest extends BaseIntegrationTest {

    @Autowired
    private MaintenanceService maintenanceService;
    @Autowired
    private ElevatorService elevatorService;

    @Test
    void compliantOnExactlyFifteenthDay() {
        elevatorService.create("EL-M15");

        MaintenanceRepository.MaintenanceRecord record = maintenanceService.register(
                "m-15", "EL-M15", "CERT-A001", "CERT-A002", null, T0.plus(15, ChronoUnit.DAYS));

        assertEquals("COMPLIANT", record.result(), "恰第 15 日完成合规");
        assertEquals(T0.toString(), record.periodStart().toString() + "T00:00:00Z");
        assertEquals(T0.plus(15, ChronoUnit.DAYS).toString(), record.dueDate().toString() + "T00:00:00Z");

        Elevator elevator = elevatorService.get("EL-M15");
        assertEquals(Elevator.STATUS_RUNNING, elevator.status(), "合规维保不得锁定电梯");
        assertFalse(elevator.keySupervision());
        assertEquals(record.dueDate(), elevator.lastMaintainedOn(), "维保周期自完成日起算");
        assertTrue(elevatorService.stopLog("EL-M15").isEmpty());
    }

    @Test
    void overdueOnSixteenthDayLocksElevator() {
        elevatorService.create("EL-M16");

        MaintenanceRepository.MaintenanceRecord record = maintenanceService.register(
                "m-16", "EL-M16", "CERT-A001", null, null, T0.plus(16, ChronoUnit.DAYS));

        assertEquals("OVERDUE", record.result(), "第 16 日完成逾期");

        Elevator elevator = elevatorService.get("EL-M16");
        assertEquals(Elevator.STATUS_STOPPED, elevator.status(), "逾期应锁定电梯");
        assertTrue(elevator.keySupervision(), "逾期应列入重点监察");
        assertTrue(elevatorService.stopLog("EL-M16").stream()
                .anyMatch(l -> l.action().equals("STOP") && l.reason().equals("MAINTENANCE_OVERDUE")));
    }

    @Test
    void compliantMaintenanceUnlocksElevator() {
        elevatorService.create("EL-MU");
        maintenanceService.register("m-u1", "EL-MU", "CERT-A001", null, null,
                T0.plus(20, ChronoUnit.DAYS));
        assertEquals(Elevator.STATUS_STOPPED, elevatorService.get("EL-MU").status());

        MaintenanceRepository.MaintenanceRecord second = maintenanceService.register(
                "m-u2", "EL-MU", "CERT-A001", null, null, T0.plus(25, ChronoUnit.DAYS));
        assertEquals("COMPLIANT", second.result(), "新周期内完成合规");

        Elevator elevator = elevatorService.get("EL-MU");
        assertEquals(Elevator.STATUS_RUNNING, elevator.status(), "合规维保后解除锁定");
        assertTrue(elevator.keySupervision(), "重点监察标记保留");
        assertTrue(elevatorService.stopLog("EL-MU").stream()
                .anyMatch(l -> l.action().equals("RESUME") && l.reason().equals("MAINTENANCE_COMPLIANT")));
    }

    @Test
    void registerIdempotentReplayAndConflict() {
        elevatorService.create("EL-MIDEM");
        Instant at = T0.plus(10, ChronoUnit.DAYS);

        MaintenanceRepository.MaintenanceRecord first = maintenanceService.register(
                "m-idem", "EL-MIDEM", "CERT-A001", null, null, at);
        MaintenanceRepository.MaintenanceRecord replay = maintenanceService.register(
                "m-idem", "EL-MIDEM", "CERT-A001", null, null, at);
        assertEquals(first.id(), replay.id(), "同键同参返回首次结果");
        assertEquals(1, maintenanceService.listByElevator("EL-MIDEM").size());

        ApiException conflict = assertThrows(ApiException.class, () -> maintenanceService.register(
                "m-idem", "EL-MIDEM", "CERT-A002", null, null, at));
        assertEquals(HttpStatus.CONFLICT, conflict.status(), "同键异参返回 409");
        assertEquals(1, maintenanceService.listByElevator("EL-MIDEM").size(), "409 不得新增记录");
    }

    @Test
    void unknownStaffRejected() {
        elevatorService.create("EL-MSTAFF");
        ApiException ex = assertThrows(ApiException.class, () -> maintenanceService.register(
                "m-staff", "EL-MSTAFF", "CERT-X999", null, null, T0));
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, ex.status(), "未持证人员不得登记维保");
        assertTrue(maintenanceService.listByElevator("EL-MSTAFF").isEmpty());
    }
}
