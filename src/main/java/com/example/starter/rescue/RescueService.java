package com.example.starter.rescue;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.starter.common.ApiException;
import com.example.starter.common.Fingerprints;
import com.example.starter.elevator.Elevator;
import com.example.starter.elevator.ElevatorRepository;
import com.example.starter.staff.StaffRepository;

/**
 * 救援案件服务：接报→派单→到场→施救→复检恢复→回访结案。
 * 接报/派单/结案按 requestId 幂等；同一电梯同时刻仅一个进行中案件；
 * 90 日（UTC 日界）内恰第 2 次困人强制停梯；接报后恰满 30 分钟未到场升级通报。
 */
@Service
public class RescueService {

    /** 90 日窗口包含的 UTC 日数（含当日）。 */
    private static final long TRAPPED_WINDOW_DAYS = 90;

    private final RescueRepository rescueRepository;
    private final ElevatorRepository elevatorRepository;
    private final StaffRepository staffRepository;
    private final Clock clock;

    public RescueService(RescueRepository rescueRepository, ElevatorRepository elevatorRepository,
                         StaffRepository staffRepository, Clock clock) {
        this.rescueRepository = rescueRepository;
        this.elevatorRepository = elevatorRepository;
        this.staffRepository = staffRepository;
        this.clock = clock;
    }

    /**
     * 接报：幂等；电梯停梯或存在进行中案件时拒绝；90 日内恰第 2 次困人触发强制停梯。
     *
     * @param requestId    幂等键
     * @param elevatorCode 电梯编号
     * @param reportedAt   接报时间（UTC），空则取当前时钟
     * @return 案件（重放时返回首次结果）
     */
    @Transactional
    public RescueCase report(String requestId, String elevatorCode, Instant reportedAt) {
        Instant at = reportedAt != null ? reportedAt : Instant.now(clock);
        String fingerprint = Fingerprints.of(requestId, elevatorCode, at);
        var existing = rescueRepository.findByRequestId(requestId);
        if (existing.isPresent()) {
            RescueCase c = existing.get();
            if (!c.reportFingerprint().equals(fingerprint)) {
                throw ApiException.conflict("requestId 已存在且业务字段不一致");
            }
            return c;
        }
        Elevator elevator = elevatorRepository.findByCodeForUpdate(elevatorCode)
                .orElseThrow(() -> ApiException.notFound("电梯不存在: " + elevatorCode));
        if (Elevator.STATUS_STOPPED.equals(elevator.status())) {
            throw ApiException.conflict("电梯已停梯，不予受理新接报");
        }
        if (rescueRepository.findActiveByElevator(elevator.id(), elevator.code()).isPresent()) {
            throw ApiException.conflict("同一电梯已存在进行中救援单");
        }
        long caseId = rescueRepository.insert(requestId, fingerprint, elevator.id(), elevator.code(), at);
        rescueRepository.insertEvent(caseId, elevator.id(), "REPORTED", "接报困人", at);

        LocalDate reportDay = LocalDate.ofInstant(at, ZoneOffset.UTC);
        Instant windowStart = reportDay.minusDays(TRAPPED_WINDOW_DAYS - 1)
                .atStartOfDay(ZoneOffset.UTC).toInstant();
        int trappedCount = rescueRepository.countReportedSince(elevator.id(), windowStart);
        if (trappedCount >= 2) {
            elevatorRepository.updateStatus(elevator.id(), Elevator.STATUS_STOPPED, caseId);
            elevatorRepository.insertStopLog(elevator.id(), "STOP", "TRAPPED_TWICE_90D", caseId, at);
            rescueRepository.insertEvent(caseId, elevator.id(), "FORCE_STOP",
                    "90日内第" + trappedCount + "次困人，强制停梯检查", at);
        }
        return rescueRepository.findById(caseId).orElseThrow();
    }

    /**
     * 派单：幂等；电梯被非本案件原因停梯（如维保逾期锁定）时拒绝，保证停梯与派单唯一终态。
     */
    @Transactional
    public RescueCase dispatch(long caseId, String requestId, String dispatcher) {
        RescueCase c = rescueRepository.findById(caseId)
                .orElseThrow(() -> ApiException.notFound("救援单不存在: " + caseId));
        Instant now = Instant.now(clock);
        String fingerprint = Fingerprints.of(requestId, caseId, dispatcher);
        if (c.dispatchRequestId() != null) {
            if (c.dispatchRequestId().equals(requestId) && c.dispatchFingerprint().equals(fingerprint)) {
                return c;
            }
            throw ApiException.conflict("案件已派单，幂等键或业务字段不一致");
        }
        Elevator elevator = elevatorRepository.findByCodeForUpdate(c.elevatorCode())
                .orElseThrow(() -> ApiException.notFound("电梯不存在: " + c.elevatorCode()));
        RescueCase locked = rescueRepository.findByIdForUpdate(caseId).orElseThrow();
        if (!RescueCase.RECEIVED.equals(locked.status())) {
            throw ApiException.conflict("案件状态不允许派单: " + locked.status());
        }
        boolean stoppedByOther = Elevator.STATUS_STOPPED.equals(elevator.status())
                && !java.util.Objects.equals(elevator.stopCaseId(), caseId);
        if (stoppedByOther) {
            throw ApiException.conflict("电梯已停梯，不得继续派单");
        }
        rescueRepository.markDispatched(caseId, requestId, fingerprint, dispatcher, now);
        rescueRepository.insertEvent(caseId, elevator.id(), "DISPATCHED", "派单: " + dispatcher, now);
        return rescueRepository.findById(caseId).orElseThrow();
    }

    /**
     * 到场：须为已派单状态；超过 30 分钟时限到场的案件保持已升级标记。
     */
    @Transactional
    public RescueCase arrive(long caseId) {
        RescueCase locked = lockCase(caseId);
        if (!RescueCase.DISPATCHED.equals(locked.status())) {
            throw ApiException.conflict("案件状态不允许到场: " + locked.status());
        }
        Instant now = Instant.now(clock);
        rescueRepository.markArrived(caseId, now);
        rescueRepository.insertEvent(caseId, locked.elevatorId(), "ARRIVED", "救援人员到场", now);
        return rescueRepository.findById(caseId).orElseThrow();
    }

    /**
     * 施救：须两名不同持证人员，单人操作不生效（422 且不变更任何状态）。
     */
    @Transactional
    public RescueCase rescue(long caseId, List<String> staffCertNos) {
        RescueCase locked = lockCase(caseId);
        if (!RescueCase.ARRIVED.equals(locked.status())) {
            throw ApiException.conflict("案件状态不允许施救: " + locked.status());
        }
        Set<String> certs = new LinkedHashSet<>(staffCertNos == null ? List.of() : staffCertNos);
        if (certs.size() != 2) {
            throw ApiException.unprocessable("施救须两名不同持证人员，单人操作不生效");
        }
        List<Long> staffIds = new ArrayList<>();
        for (String cert : certs) {
            var staff = staffRepository.findByCertNo(cert)
                    .orElseThrow(() -> ApiException.unprocessable("人员未持证登记: " + cert));
            staffIds.add(staff.id());
        }
        Instant now = Instant.now(clock);
        rescueRepository.markRescued(caseId, staffIds.get(0), staffIds.get(1), now);
        rescueRepository.insertEvent(caseId, locked.elevatorId(), "RESCUED",
                "双人施救完成: " + String.join(",", certs), now);
        return rescueRepository.findById(caseId).orElseThrow();
    }

    /**
     * 复检恢复：复检合格与电梯复梯在同一事务内完成；不合格则 422 且不得复梯。
     */
    @Transactional
    public RescueCase recheck(long caseId, String result) {
        RescueCase locked = lockCase(caseId);
        if (!RescueCase.RESCUING.equals(locked.status())) {
            throw ApiException.conflict("案件状态不允许复检: " + locked.status());
        }
        if (!"PASS".equals(result)) {
            throw ApiException.unprocessable("复检不合格，不得恢复运行");
        }
        Instant now = Instant.now(clock);
        rescueRepository.markRechecked(caseId, result, now);
        Elevator elevator = elevatorRepository.findByCodeForUpdate(locked.elevatorCode()).orElseThrow();
        if (Elevator.STATUS_STOPPED.equals(elevator.status())) {
            elevatorRepository.updateStatus(elevator.id(), Elevator.STATUS_RUNNING, null);
            elevatorRepository.insertStopLog(elevator.id(), "RESUME", "RECHECK_PASS", caseId, now);
        }
        rescueRepository.insertEvent(caseId, locked.elevatorId(), "RECHECKED", "复检合格，恢复运行", now);
        return rescueRepository.findById(caseId).orElseThrow();
    }

    /**
     * 回访结案：幂等；CLOSED 为终态，重复结案返回幂等成功，结案后不再接受任何状态变更。
     */
    @Transactional
    public RescueCase close(long caseId, String requestId, String feedback) {
        RescueCase c = rescueRepository.findById(caseId)
                .orElseThrow(() -> ApiException.notFound("救援单不存在: " + caseId));
        String fingerprint = Fingerprints.of(requestId, caseId, feedback);
        if (RescueCase.CLOSED.equals(c.status())) {
            if (c.closeRequestId() != null && c.closeRequestId().equals(requestId)
                    && !c.closeFingerprint().equals(fingerprint)) {
                throw ApiException.conflict("结案幂等键相同但业务字段不一致");
            }
            return c;
        }
        RescueCase locked = lockCase(caseId);
        if (!RescueCase.RECHECKED.equals(locked.status())) {
            throw ApiException.conflict("案件状态不允许结案: " + locked.status());
        }
        Instant now = Instant.now(clock);
        rescueRepository.markClosed(caseId, requestId, fingerprint, now);
        rescueRepository.insertEvent(caseId, locked.elevatorId(), "CLOSED",
                "回访结案" + (feedback == null ? "" : ": " + feedback), now);
        return rescueRepository.findById(caseId).orElseThrow();
    }

    /**
     * 超时扫描：接报后恰满 30 分钟未到场的案件升级通报（幂等，已升级不重复）。
     *
     * @return 本次升级的案件 ID 列表
     */
    @Transactional
    public List<Long> escalateOverdue() {
        Instant now = Instant.now(clock);
        List<Long> escalated = new ArrayList<>();
        for (RescueCase c : rescueRepository.findPendingArrival()) {
            Instant deadline = c.reportedAt().plusSeconds(RescueCase.ARRIVE_TIMEOUT_MINUTES * 60);
            if (!deadline.isAfter(now)) {
                rescueRepository.markEscalated(c.id(), now);
                rescueRepository.insertEvent(c.id(), c.elevatorId(), "ESCALATED",
                        "接报后30分钟未到场，超时升级通报", now);
                escalated.add(c.id());
            }
        }
        return escalated;
    }

    /**
     * 查询案件。
     */
    @Transactional(readOnly = true)
    public RescueCase getCase(long caseId) {
        return rescueRepository.findById(caseId)
                .orElseThrow(() -> ApiException.notFound("救援单不存在: " + caseId));
    }

    /**
     * 查询案件时间线（可复算）。
     */
    @Transactional(readOnly = true)
    public List<RescueRepository.CaseEvent> timeline(long caseId) {
        return rescueRepository.listEvents(caseId);
    }

    private RescueCase lockCase(long caseId) {
        return rescueRepository.findByIdForUpdate(caseId)
                .orElseThrow(() -> ApiException.notFound("救援单不存在: " + caseId));
    }
}
