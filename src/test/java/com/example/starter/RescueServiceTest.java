package com.example.starter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;

import com.example.starter.common.ApiException;
import com.example.starter.elevator.Elevator;
import com.example.starter.elevator.ElevatorRepository;
import com.example.starter.elevator.ElevatorService;
import com.example.starter.maintenance.MaintenanceRepository;
import com.example.starter.maintenance.MaintenanceService;
import com.example.starter.rescue.RescueCase;
import com.example.starter.rescue.RescueRepository;
import com.example.starter.rescue.RescueService;

/**
 * 救援案件主流程与边界测试：超时升级、双人施救、90 日停梯、复梯复检原子性、
 * 幂等重放、结案终态、结案后迟到维保。
 */
class RescueServiceTest extends BaseIntegrationTest {

    @Autowired
    private RescueService rescueService;
    @Autowired
    private ElevatorService elevatorService;
    @Autowired
    private ElevatorRepository elevatorRepository;
    @Autowired
    private RescueRepository rescueRepository;
    @Autowired
    private MaintenanceService maintenanceService;

    private RescueCase newCase(String code, String requestId, Instant reportedAt) {
        elevatorService.create(code);
        return rescueService.report(requestId, code, reportedAt);
    }

    /** 推进案件到已到场。 */
    private RescueCase arriveAt(long caseId, String dispatchKey) {
        rescueService.dispatch(caseId, dispatchKey, "调度员");
        return rescueService.arrive(caseId);
    }

    /** 推进案件到结案（电梯未被停梯的常规路径）。 */
    private RescueCase driveToClose(long caseId, String keyPrefix) {
        arriveAt(caseId, keyPrefix + "-d");
        rescueService.rescue(caseId, List.of("CERT-A001", "CERT-A002"));
        rescueService.recheck(caseId, "PASS");
        return rescueService.close(caseId, keyPrefix + "-c", "回访满意");
    }

    @Test
    void fullFlowTransitionsAndTimeline() {
        RescueCase c = newCase("EL-1", "r-1", T0);
        assertEquals(RescueCase.RECEIVED, c.status());

        RescueCase dispatched = rescueService.dispatch(c.id(), "d-1", "调度员");
        assertEquals(RescueCase.DISPATCHED, dispatched.status());
        assertNotNull(dispatched.dispatchedAt());

        RescueCase arrived = rescueService.arrive(c.id());
        assertEquals(RescueCase.ARRIVED, arrived.status());

        RescueCase rescued = rescueService.rescue(c.id(), List.of("CERT-A001", "CERT-A002"));
        assertEquals(RescueCase.RESCUING, rescued.status());
        assertNotNull(rescued.rescuer1Id());
        assertNotNull(rescued.rescuer2Id());

        RescueCase rechecked = rescueService.recheck(c.id(), "PASS");
        assertEquals(RescueCase.RECHECKED, rechecked.status());

        RescueCase closed = rescueService.close(c.id(), "c-1", "回访满意");
        assertEquals(RescueCase.CLOSED, closed.status());
        assertNotNull(closed.closedAt());

        List<String> events = rescueRepository.listEvents(c.id()).stream()
                .map(RescueRepository.CaseEvent::eventType).toList();
        assertEquals(List.of("REPORTED", "DISPATCHED", "ARRIVED", "RESCUED", "RECHECKED", "CLOSED"), events);
    }

    @Test
    void escalatesAtExactlyThirtyMinutes() {
        RescueCase c = newCase("EL-30", "r-30", T0);

        clock.set(T0.plus(29, ChronoUnit.MINUTES).plusSeconds(59));
        assertTrue(rescueService.escalateOverdue().isEmpty(), "未满 30 分钟不得升级");
        assertFalse(rescueService.getCase(c.id()).escalated());

        clock.set(T0.plus(30, ChronoUnit.MINUTES));
        List<Long> escalated = rescueService.escalateOverdue();
        assertEquals(List.of(c.id()), escalated, "恰满 30 分钟未到场应升级通报");

        RescueCase after = rescueService.getCase(c.id());
        assertTrue(after.escalated());
        assertEquals(T0.plus(30, ChronoUnit.MINUTES), after.escalatedAt());
        assertTrue(rescueRepository.listEvents(c.id()).stream()
                .anyMatch(e -> e.eventType().equals("ESCALATED")));

        assertTrue(rescueService.escalateOverdue().isEmpty(), "升级幂等，不重复通报");
    }

    @Test
    void rescueRequiresTwoDistinctCertifiedStaff() {
        RescueCase c = newCase("EL-2P", "r-2p", T0);
        arriveAt(c.id(), "d-2p");

        ApiException single = assertThrows(ApiException.class,
                () -> rescueService.rescue(c.id(), List.of("CERT-A001")));
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, single.status(), "单人施救不生效");

        ApiException duplicated = assertThrows(ApiException.class,
                () -> rescueService.rescue(c.id(), List.of("CERT-A001", "CERT-A001")));
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, duplicated.status(), "同一人员重复不算双人");

        ApiException unknown = assertThrows(ApiException.class,
                () -> rescueService.rescue(c.id(), List.of("CERT-A001", "CERT-X999")));
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, unknown.status(), "未持证人员不得施救");

        RescueCase after = rescueService.getCase(c.id());
        assertEquals(RescueCase.ARRIVED, after.status(), "失败施救不得改变案件状态");
        assertNull(after.rescuedAt());
        assertTrue(rescueRepository.listEvents(c.id()).stream()
                .noneMatch(e -> e.eventType().equals("RESCUED")));

        RescueCase ok = rescueService.rescue(c.id(), List.of("CERT-A001", "CERT-A002"));
        assertEquals(RescueCase.RESCUING, ok.status(), "两名持证人员施救生效");
    }

    @Test
    void reportIdempotentReplayAndConflict() {
        RescueCase first = newCase("EL-IDEM", "r-idem", T0);

        RescueCase replay = rescueService.report("r-idem", "EL-IDEM", T0);
        assertEquals(first.id(), replay.id(), "同键同参返回首次结果");

        ApiException conflict = assertThrows(ApiException.class,
                () -> rescueService.report("r-idem", "EL-IDEM", T0.plusSeconds(60)));
        assertEquals(HttpStatus.CONFLICT, conflict.status(), "同键异参返回 409");

        Elevator elevator = elevatorService.get("EL-IDEM");
        assertEquals(Elevator.STATUS_RUNNING, elevator.status(), "409 不得改变电梯状态");
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM rescue_case WHERE elevator_code = 'EL-IDEM'", Integer.class));
    }

    @Test
    void dispatchIdempotentReplayAndConflict() {
        RescueCase c = newCase("EL-DISP", "r-disp", T0);

        RescueCase dispatched = rescueService.dispatch(c.id(), "d-disp", "调度员A");
        RescueCase replay = rescueService.dispatch(c.id(), "d-disp", "调度员A");
        assertEquals(dispatched.dispatchedAt(), replay.dispatchedAt(), "同键同参返回首次派单结果");

        ApiException conflict = assertThrows(ApiException.class,
                () -> rescueService.dispatch(c.id(), "d-disp", "调度员B"));
        assertEquals(HttpStatus.CONFLICT, conflict.status(), "同键异参返回 409");

        ApiException otherKey = assertThrows(ApiException.class,
                () -> rescueService.dispatch(c.id(), "d-other", "调度员A"));
        assertEquals(HttpStatus.CONFLICT, otherKey.status(), "已派单案件换键重派返回 409");
        assertEquals(RescueCase.DISPATCHED, rescueService.getCase(c.id()).status());
    }

    @Test
    void closeIsTerminalAndIdempotent() {
        RescueCase c = newCase("EL-TERM", "r-term", T0);
        RescueCase closed = driveToClose(c.id(), "k-term");
        assertEquals(RescueCase.CLOSED, closed.status());

        RescueCase replay = rescueService.close(c.id(), "k-term-c", "回访满意");
        assertEquals(closed.closedAt(), replay.closedAt(), "重复结案返回幂等成功");

        RescueCase otherKey = rescueService.close(c.id(), "k-term-other", "再次回访");
        assertEquals(closed.closedAt(), otherKey.closedAt(), "终态下重复结案幂等成功且不变更");

        ApiException conflict = assertThrows(ApiException.class,
                () -> rescueService.close(c.id(), "k-term-c", "不同反馈"));
        assertEquals(HttpStatus.CONFLICT, conflict.status(), "同键异参返回 409");

        ApiException dispatchAfterClose = assertThrows(ApiException.class,
                () -> rescueService.dispatch(c.id(), "d-after", "调度员"));
        assertEquals(HttpStatus.CONFLICT, dispatchAfterClose.status(), "结案后不得再派单");
        assertEquals(RescueCase.CLOSED, rescueService.getCase(c.id()).status());
    }

    @Test
    void secondTrappedWithinNinetyDaysForcesStop() {
        RescueCase first = newCase("EL-90", "r-90-1", T0);
        driveToClose(first.id(), "k-90-1");
        assertEquals(Elevator.STATUS_RUNNING, elevatorService.get("EL-90").status(),
                "首次困人不得停梯");

        Instant secondAt = T0.plus(89, ChronoUnit.DAYS).plus(12, ChronoUnit.HOURS);
        RescueCase second = rescueService.report("r-90-2", "EL-90", secondAt);

        Elevator elevator = elevatorService.get("EL-90");
        assertEquals(Elevator.STATUS_STOPPED, elevator.status(), "90 日内恰第 2 次困人应强制停梯");
        assertEquals(second.id(), elevator.stopCaseId());
        assertTrue(elevatorService.stopLog("EL-90").stream()
                .anyMatch(l -> l.action().equals("STOP") && l.reason().equals("TRAPPED_TWICE_90D")));
        assertTrue(rescueRepository.listEvents(second.id()).stream()
                .anyMatch(e -> e.eventType().equals("FORCE_STOP")));
    }

    @Test
    void trappedAfterNinetyUtcDaysDoesNotStop() {
        RescueCase first = newCase("EL-91", "r-91-1", T0);
        driveToClose(first.id(), "k-91-1");

        Instant secondAt = T0.plus(90, ChronoUnit.DAYS).plus(1, ChronoUnit.HOURS);
        RescueCase second = rescueService.report("r-91-2", "EL-91", secondAt);

        Elevator elevator = elevatorService.get("EL-91");
        assertEquals(Elevator.STATUS_RUNNING, elevator.status(),
                "第 1 次困人超出 90 日（UTC 日界）窗口，不得停梯");
        assertEquals(RescueCase.RECEIVED, second.status());
        assertTrue(elevatorService.stopLog("EL-91").isEmpty());
    }

    @Test
    void recheckAndResumeAreAtomic() {
        RescueCase first = newCase("EL-ATOM", "r-atom-1", T0);
        driveToClose(first.id(), "k-atom-1");
        RescueCase second = rescueService.report("r-atom-2", "EL-ATOM", T0.plus(10, ChronoUnit.DAYS));
        assertEquals(Elevator.STATUS_STOPPED, elevatorService.get("EL-ATOM").status());

        RescueCase dispatched = rescueService.dispatch(second.id(), "d-atom-2", "调度员");
        assertEquals(RescueCase.DISPATCHED, dispatched.status(), "本案件触发的停梯允许继续救援派单");
        rescueService.arrive(second.id());
        rescueService.rescue(second.id(), List.of("CERT-A001", "CERT-A002"));

        ApiException fail = assertThrows(ApiException.class,
                () -> rescueService.recheck(second.id(), "FAIL"));
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, fail.status(), "复检不合格不得复梯");

        Elevator stopped = elevatorService.get("EL-ATOM");
        assertEquals(Elevator.STATUS_STOPPED, stopped.status(), "复检失败不得产生部分复梯");
        assertTrue(elevatorService.stopLog("EL-ATOM").stream()
                .noneMatch(l -> l.action().equals("RESUME")), "不得先复梯后补检");
        assertEquals(RescueCase.RESCUING, rescueService.getCase(second.id()).status());

        RescueCase rechecked = rescueService.recheck(second.id(), "PASS");
        assertEquals(RescueCase.RECHECKED, rechecked.status());
        Elevator resumed = elevatorService.get("EL-ATOM");
        assertEquals(Elevator.STATUS_RUNNING, resumed.status(), "复检合格与复梯同事务完成");
        assertNull(resumed.stopCaseId());
        assertTrue(elevatorService.stopLog("EL-ATOM").stream()
                .anyMatch(l -> l.action().equals("RESUME") && l.reason().equals("RECHECK_PASS")
                        && l.caseId().equals(second.id())));
    }

    @Test
    void dispatchRejectedWhenElevatorLockedByOverdueMaintenance() {
        RescueCase c = newCase("EL-LOCK", "r-lock", T0);

        maintenanceService.register("m-lock", "EL-LOCK", "CERT-A001", null, null,
                T0.plus(16, ChronoUnit.DAYS));
        assertEquals(Elevator.STATUS_STOPPED, elevatorService.get("EL-LOCK").status());

        ApiException conflict = assertThrows(ApiException.class,
                () -> rescueService.dispatch(c.id(), "d-lock", "调度员"));
        assertEquals(HttpStatus.CONFLICT, conflict.status(), "维保逾期锁定后不得继续派单");
        assertEquals(RescueCase.RECEIVED, rescueService.getCase(c.id()).status());
    }

    @Test
    void lateMaintenanceAfterCloseOnlyRecordsEvent() {
        RescueCase c = newCase("EL-LATE", "r-late", T0);
        driveToClose(c.id(), "k-late");
        Elevator before = elevatorService.get("EL-LATE");

        MaintenanceRepository.MaintenanceRecord late = maintenanceService.register(
                "m-late", "EL-LATE", "CERT-A001", null, c.id(), T0.plus(20, ChronoUnit.DAYS));

        assertEquals("LATE_EVENT", late.result(), "结案后迟到维保只记迟到事件");
        assertEquals(RescueCase.CLOSED, rescueService.getCase(c.id()).status(), "案件终态不变");
        Elevator after = elevatorService.get("EL-LATE");
        assertEquals(before.status(), after.status(), "电梯状态不变");
        assertEquals(before.lastMaintainedOn(), after.lastMaintainedOn(), "维保周期不变");
        assertTrue(rescueRepository.listEvents(c.id()).stream()
                .anyMatch(e -> e.eventType().equals("LATE_MAINTENANCE")));
    }
}
