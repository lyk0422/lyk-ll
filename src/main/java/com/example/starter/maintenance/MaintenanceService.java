package com.example.starter.maintenance;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.starter.common.ApiException;
import com.example.starter.common.Fingerprints;
import com.example.starter.elevator.Elevator;
import com.example.starter.elevator.ElevatorRepository;
import com.example.starter.rescue.RescueCase;
import com.example.starter.rescue.RescueRepository;
import com.example.starter.staff.StaffRepository;

/**
 * 维保服务：周期 15 日，恰第 15 日完成合规；逾期锁定电梯并列入重点监察；
 * 结案后迟到的维保记录只记迟到事件，不改变案件与电梯状态。
 */
@Service
public class MaintenanceService {

    private final MaintenanceRepository maintenanceRepository;
    private final ElevatorRepository elevatorRepository;
    private final RescueRepository rescueRepository;
    private final StaffRepository staffRepository;
    private final Clock clock;

    public MaintenanceService(MaintenanceRepository maintenanceRepository,
                              ElevatorRepository elevatorRepository,
                              RescueRepository rescueRepository,
                              StaffRepository staffRepository,
                              Clock clock) {
        this.maintenanceRepository = maintenanceRepository;
        this.elevatorRepository = elevatorRepository;
        this.rescueRepository = rescueRepository;
        this.staffRepository = staffRepository;
        this.clock = clock;
    }

    /**
     * 登记维保记录：按 requestId 幂等。
     *
     * @param requestId    幂等键
     * @param elevatorCode 电梯编号
     * @param staff1Cert   维保人员1 持证编号（必填）
     * @param staff2Cert   维保人员2 持证编号（可空）
     * @param caseId       关联救援单（可空；结案后迟到仅记事件）
     * @param completedAt  完成时间（UTC），空则取当前时钟
     * @return 维保记录（重放时返回首次结果）
     */
    @Transactional
    public MaintenanceRepository.MaintenanceRecord register(String requestId, String elevatorCode,
                                                            String staff1Cert, String staff2Cert,
                                                            Long caseId, Instant completedAt) {
        Instant at = completedAt != null ? completedAt : Instant.now(clock);
        String fingerprint = Fingerprints.of(requestId, elevatorCode, staff1Cert, staff2Cert, caseId, at);
        var existing = maintenanceRepository.findByRequestId(requestId);
        if (existing.isPresent()) {
            MaintenanceRepository.MaintenanceRecord r = existing.get();
            if (!r.fingerprint().equals(fingerprint)) {
                throw ApiException.conflict("requestId 已存在且业务字段不一致");
            }
            return r;
        }
        var staff1 = staffRepository.findByCertNo(staff1Cert)
                .orElseThrow(() -> ApiException.unprocessable("人员未持证登记: " + staff1Cert));
        Long staff2Id = null;
        if (staff2Cert != null && !staff2Cert.isBlank()) {
            staff2Id = staffRepository.findByCertNo(staff2Cert)
                    .orElseThrow(() -> ApiException.unprocessable("人员未持证登记: " + staff2Cert))
                    .id();
        }
        Elevator elevator = elevatorRepository.findByCodeForUpdate(elevatorCode)
                .orElseThrow(() -> ApiException.notFound("电梯不存在: " + elevatorCode));

        if (caseId != null) {
            RescueCase c = rescueRepository.findById(caseId)
                    .orElseThrow(() -> ApiException.notFound("救援单不存在: " + caseId));
            if (RescueCase.CLOSED.equals(c.status())) {
                MaintenanceRepository.MaintenanceRecord late = new MaintenanceRepository.MaintenanceRecord(
                        0, requestId, fingerprint, elevator.id(), staff1.id(), staff2Id, caseId,
                        elevator.lastMaintainedOn(),
                        elevator.lastMaintainedOn().plusDays(MaintenanceRepository.CYCLE_DAYS),
                        at, "LATE_EVENT", at);
                long id = maintenanceRepository.insert(late);
                rescueRepository.insertEvent(caseId, elevator.id(), "LATE_MAINTENANCE",
                        "结案后迟到维保记录，仅记事件", at);
                return maintenanceRepository.findByRequestId(requestId).orElseThrow();
            }
        }

        LocalDate completedDate = LocalDate.ofInstant(at, ZoneOffset.UTC);
        LocalDate periodStart = elevator.lastMaintainedOn();
        LocalDate dueDate = periodStart.plusDays(MaintenanceRepository.CYCLE_DAYS);
        boolean compliant = !completedDate.isAfter(dueDate);
        String result = compliant ? "COMPLIANT" : "OVERDUE";

        MaintenanceRepository.MaintenanceRecord record = new MaintenanceRepository.MaintenanceRecord(
                0, requestId, fingerprint, elevator.id(), staff1.id(), staff2Id, caseId,
                periodStart, dueDate, at, result, at);
        maintenanceRepository.insert(record);
        elevatorRepository.updateLastMaintainedOn(elevator.id(), completedDate);

        if (!compliant) {
            elevatorRepository.updateStatus(elevator.id(), Elevator.STATUS_STOPPED, null);
            elevatorRepository.updateKeySupervision(elevator.id(), true);
            elevatorRepository.insertStopLog(elevator.id(), "STOP", "MAINTENANCE_OVERDUE", caseId, at);
        } else if (Elevator.STATUS_STOPPED.equals(elevator.status()) && elevator.stopCaseId() == null) {
            elevatorRepository.updateStatus(elevator.id(), Elevator.STATUS_RUNNING, null);
            elevatorRepository.insertStopLog(elevator.id(), "RESUME", "MAINTENANCE_COMPLIANT", caseId, at);
        }
        return maintenanceRepository.findByRequestId(requestId).orElseThrow();
    }

    /**
     * 查询电梯维保台账（可复算）。
     */
    @Transactional(readOnly = true)
    public List<MaintenanceRepository.MaintenanceRecord> listByElevator(String elevatorCode) {
        Elevator elevator = elevatorRepository.findByCode(elevatorCode)
                .orElseThrow(() -> ApiException.notFound("电梯不存在: " + elevatorCode));
        return maintenanceRepository.listByElevator(elevator.id());
    }
}
