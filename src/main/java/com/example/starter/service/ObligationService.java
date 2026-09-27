package com.example.starter.service;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import com.example.starter.domain.ChainAction;
import com.example.starter.domain.MaintenanceResult;
import com.example.starter.domain.StopReason;
import com.example.starter.error.ApiException;
import com.example.starter.service.ElevatorService.ElevatorRow;
import com.example.starter.web.dto.ApiDtos.ObligationAllocateRequest;
import com.example.starter.web.dto.ApiDtos.ObligationAttemptView;
import com.example.starter.web.dto.ApiDtos.ObligationCompleteRequest;
import com.example.starter.web.dto.ApiDtos.ObligationEventView;
import com.example.starter.web.dto.ApiDtos.ObligationItemsRequest;
import com.example.starter.web.dto.ApiDtos.ObligationStartRequest;
import com.example.starter.web.dto.ApiDtos.ObligationView;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 维保履约续派：义务（同电梯同到期周期唯一）→ 尝试（可被救援挂起、结案后续派）。
 * <ul>
 *   <li>到期前 24 小时（恰在起点允许）起可派发；重复扫描不重复建义务；</li>
 *   <li>尝试占用两名持证人员半开作业窗 [start,end)，同窗不重叠，任一不足整批不占资源；</li>
 *   <li>开始时重查两人证书有效且无进行中救援；</li>
 *   <li>接报救援原子挂起未完成尝试、释放人员、保留清单，不改变到期义务；</li>
 *   <li>结案后可续派新尝试；旧尝试回报只记迟到，不算本周期维保完成；</li>
 *   <li>新尝试完成全部清单并双人共同确认，登记记录/终结义务/释放资源同事务。</li>
 * </ul>
 */
@Service
public class ObligationService {

    /** 维保周期（日），与手工台账一致 */
    static final long CYCLE_DAYS = MaintenanceService.CYCLE_DAYS;
    /** 到期前可派发的提前量 */
    static final long DISPATCH_BEFORE_HOURS = 24;

    /** 固定合成检查项编码（合成规则，非生产） */
    static final List<String> CHECKLIST_CODES =
            List.of("CHK_CAR_DOOR", "CHK_EMERGENCY_ALARM", "CHK_TRACTION_BRAKE", "CHK_LANDING_LOCK");

    private static final long EARLY_DISPATCH_SECONDS = DISPATCH_BEFORE_HOURS * 3600;

    private record ObligationRow(long id, long elevatorId, int periodIndex, LocalDate baseDay, Instant dueAt,
                                 String status, int version, Long activeAttemptId, Long lastValidRecordId,
                                 Long fulfilledRecordId, Instant fulfilledAt) {
    }

    private record AttemptRow(long id, long obligationId, int seq, String status, int version,
                              String allocateRequestId, String startRequestId, String completeRequestId,
                              Instant allocatedAt, Instant startedAt, Instant suspendedAt, Instant completedAt,
                              Long suspendRescueId) {
    }

    private final JdbcTemplate jdbc;
    private final ElevatorService elevatorService;
    private final IdempotencyService idempotency;
    private final Clock clock;

    public ObligationService(JdbcTemplate jdbc, ElevatorService elevatorService,
                             IdempotencyService idempotency, Clock clock) {
        this.jdbc = jdbc;
        this.elevatorService = elevatorService;
        this.idempotency = idempotency;
        this.clock = clock;
    }

    // ---------------------------------------------------------------- 扫描

    /** 控制器扫描入口：返回该电梯当前全部义务。 */
    @Transactional
    public List<ObligationView> scan(long elevatorId, Instant at) {
        Instant now = at != null ? at : Instant.now(clock);
        ElevatorRow ev = elevatorService.lockElevator(elevatorId);
        ValidBase base = currentValidBase(ev);
        int nextPeriod = base.validCount() + 1;
        Instant dueAt = dueAtFor(base.baseDay());
        if (!now.isBefore(dueAt.minusSeconds(DISPATCH_BEFORE_HOURS * 3600))) {
            List<Long> existing = jdbc.query(
                    "SELECT id FROM maintenance_obligation WHERE elevator_id=? AND period_index=?",
                    (rs, i) -> rs.getLong(1), elevatorId, nextPeriod);
            if (existing.isEmpty()) {
                createObligation(ev.id(), nextPeriod, base, dueAt, now);
            }
        }
        return listForElevator(elevatorId);
    }

    private long createObligation(long elevatorId, int period, ValidBase base, Instant dueAt, Instant now) {
        try {
            jdbc.update("INSERT INTO maintenance_obligation(elevator_id, period_index, base_day, due_at, "
                            + "last_valid_record_id, status, version, created_at, updated_at) "
                            + "VALUES (?,?,?,?,?,'OPEN',1,?,?)",
                    elevatorId, period, base.baseDay(), ElevatorService.ts(dueAt),
                    base.lastRecordId(), ElevatorService.ts(now), ElevatorService.ts(now));
        } catch (DuplicateKeyException e) {
            // 并发重复扫描：义务已由胜出方创建，不多派
            Long id = jdbc.queryForObject(
                    "SELECT id FROM maintenance_obligation WHERE elevator_id=? AND period_index=?",
                    Long.class, elevatorId, period);
            return id == null ? 0L : id;
        }
        long id = jdbc.queryForObject(
                "SELECT id FROM maintenance_obligation WHERE elevator_id=? AND period_index=?",
                Long.class, elevatorId, period);
        for (String code : CHECKLIST_CODES) {
            jdbc.update("INSERT INTO maintenance_obligation_checklist(obligation_id, item_code, item_name) VALUES (?,?,?)",
                    id, code, checklistName(code));
        }
        appendEvent(id, null, "OBLIGATION_CREATED",
                "第" + period + "周期义务生成，到期时刻 " + dueAt, now);
        return id;
    }

    // ---------------------------------------------------------------- 派发/续派

    public ResponseEntity<String> allocate(long obligationId, ObligationAllocateRequest req) {
        List<Long> staff = sortedDistinct(req.staffIds());
        String fp = Fingerprints.structured(
                List.of("scope=OBLIGATION_ALLOCATE", "requestId=" + req.requestId(),
                        "obligationId=" + obligationId, "expectedVersion=" + req.expectedVersion(),
                        "windowStart=" + req.windowStart(), "windowEnd=" + req.windowEnd()),
                List.of(staff.stream().map(String::valueOf).toList()));
        return idempotency.handle("OBLIGATION_ALLOCATE", req.requestId(), fp, () -> doAllocate(obligationId, req, staff));
    }

    private ObligationView doAllocate(long obligationId, ObligationAllocateRequest req, List<Long> staffIds) {
        if (!req.windowStart().isBefore(req.windowEnd())) {
            throw ApiException.badRequest("INVALID_WINDOW", "作业窗终点必须晚于起点（半开区间 [start,end)）");
        }
        // 统一加锁顺序：电梯 → 义务，避免与接报挂起死锁
        long elevatorId = readObligation(obligationId).elevatorId();
        ElevatorRow ev = elevatorService.lockElevator(elevatorId);
        ObligationRow ob = lockObligation(obligationId);
        if (idempotency.exists("OBLIGATION_ALLOCATE", req.requestId())) {
            return view(obligationId);
        }
        if (req.expectedVersion() != ob.version()) {
            throw ApiException.conflict("VERSION_CONFLICT",
                    "义务版本不匹配：expected=" + req.expectedVersion() + ", actual=" + ob.version());
        }
        if ("FULFILLED".equals(ob.status())) {
            throw ApiException.unprocessable("OBLIGATION_FULFILLED", "本周期义务已结清，不得再派发");
        }
        if (ob.activeAttemptId() != null || "IN_PROGRESS".equals(ob.status())) {
            throw ApiException.unprocessable("ATTEMPT_IN_FLIGHT", "已有进行中的履约尝试，重复扫描/派发不多占");
        }
        Instant dispatchAt = req.windowStart();
        Instant earliest = ob.dueAt().minusSeconds(EARLY_DISPATCH_SECONDS);
        if (dispatchAt.isBefore(earliest)) {
            throw ApiException.unprocessable("DISPATCH_WINDOW_NOT_OPEN",
                    "到期前24小时方可派发，最早派发时刻 " + earliest + "，恰在起点允许");
        }
        if (elevatorService.hasActiveRescue(ev.id())) {
            throw ApiException.unprocessable("RESCUE_IN_FLIGHT", "存在进行中的救援单，须救援结案后续派");
        }
        requireTwoCertifiedStaff(staffIds);
        requireWindowsFree(staffIds, req.windowStart(), req.windowEnd());

        Instant now = Instant.now(clock);
        Integer maxSeq = jdbc.queryForObject("SELECT MAX(seq) FROM maintenance_attempt WHERE obligation_id=?",
                Integer.class, obligationId);
        int seq = (maxSeq == null ? 0 : maxSeq) + 1;
        jdbc.update("INSERT INTO maintenance_attempt(obligation_id, seq, status, version, allocate_request_id, "
                        + "allocated_at, created_at, updated_at) VALUES (?,?,'ALLOCATED',1,?,?,?,?)",
                obligationId, seq, req.requestId(), ElevatorService.ts(dispatchAt),
                ElevatorService.ts(now), ElevatorService.ts(now));
        long attemptId = jdbc.queryForObject(
                "SELECT id FROM maintenance_attempt WHERE obligation_id=? AND seq=?", Long.class, obligationId, seq);
        for (Long staffId : staffIds) {
            jdbc.update("INSERT INTO maintenance_attempt_staff(attempt_id, staff_id, window_start, window_end, created_at) "
                            + "VALUES (?,?,?,?,?)",
                    attemptId, staffId, ElevatorService.ts(req.windowStart()),
                    ElevatorService.ts(req.windowEnd()), ElevatorService.ts(now));
        }
        jdbc.update("UPDATE maintenance_obligation SET status='IN_PROGRESS', active_attempt_id=?, version=version+1, "
                        + "updated_at=? WHERE id=?",
                attemptId, ElevatorService.ts(now), obligationId);
        appendEvent(obligationId, attemptId, "ATTEMPT_ALLOCATED",
                (seq == 1 ? "派发履约尝试" : "救援结案后续派新尝试") + " #" + seq + "，人员 " + staffIds
                        + "，作业窗 [" + req.windowStart() + "," + req.windowEnd() + ")", now);
        return view(obligationId);
    }

    // ---------------------------------------------------------------- 开始

    public ResponseEntity<String> start(long attemptId, ObligationStartRequest req) {
        String fp = Fingerprints.structured(
                "scope=OBLIGATION_START", "requestId=" + req.requestId(),
                "attemptId=" + attemptId, "expectedVersion=" + req.expectedVersion(),
                "startedAt=" + req.startedAt());
        return idempotency.handle("OBLIGATION_START", req.requestId(), fp, () -> doStart(attemptId, req));
    }

    private ObligationView doStart(long attemptId, ObligationStartRequest req) {
        // 统一加锁顺序：电梯 → 义务 → 尝试，避免与接报挂起死锁
        long obligationId = readAttempt(attemptId).obligationId();
        elevatorService.lockElevator(readObligation(obligationId).elevatorId());
        ObligationRow ob = lockObligation(obligationId);
        AttemptRow attempt = lockAttempt(attemptId);
        if (idempotency.exists("OBLIGATION_START", req.requestId())) {
            return view(ob.id());
        }
        if (req.expectedVersion() != attempt.version()) {
            throw ApiException.conflict("VERSION_CONFLICT",
                    "尝试版本不匹配：expected=" + req.expectedVersion() + ", actual=" + attempt.version());
        }
        if (!"ALLOCATED".equals(attempt.status())) {
            throw ApiException.unprocessable("ILLEGAL_ATTEMPT_STATE", "当前尝试状态不允许开始: " + attempt.status());
        }
        // 开始时重查：两人证书仍有效，且无进行中救援
        List<Long> staffIds = attemptStaff(attemptId);
        requireTwoCertifiedStaff(staffIds);
        if (elevatorService.hasActiveRescue(ob.elevatorId())) {
            throw ApiException.unprocessable("RESCUE_IN_FLIGHT", "存在进行中的救援单，开始被拒绝");
        }
        Instant now = Instant.now(clock);
        jdbc.update("UPDATE maintenance_attempt SET status='IN_PROGRESS', version=version+1, start_request_id=?, "
                        + "started_at=?, updated_at=? WHERE id=?",
                req.requestId(), ElevatorService.ts(req.startedAt()), ElevatorService.ts(now), attemptId);
        appendEvent(ob.id(), attemptId, "ATTEMPT_STARTED", "尝试开始，开始时复核双人持证且无进行中救援", now);
        return view(ob.id());
    }

    // ---------------------------------------------------------------- 清单回报

    public ResponseEntity<String> reportItems(long attemptId, ObligationItemsRequest req) {
        List<String> items = sortedDistinct(req.doneItems());
        String fp = Fingerprints.structured(
                List.of("scope=OBLIGATION_ITEM", "requestId=" + req.requestId(),
                        "attemptId=" + attemptId, "expectedVersion=" + req.expectedVersion(), "at=" + req.at()),
                List.of(items));
        return idempotency.handle("OBLIGATION_ITEM", req.requestId(), fp, () -> doReportItems(attemptId, req, items));
    }

    private ObligationView doReportItems(long attemptId, ObligationItemsRequest req, List<String> items) {
        // 统一加锁顺序：电梯 → 义务 → 尝试
        long obligationId = readAttempt(attemptId).obligationId();
        elevatorService.lockElevator(readObligation(obligationId).elevatorId());
        ObligationRow ob = lockObligation(obligationId);
        AttemptRow attempt = lockAttempt(attemptId);
        if (idempotency.exists("OBLIGATION_ITEM", req.requestId())) {
            return view(ob.id());
        }
        if (req.expectedVersion() != attempt.version()) {
            throw ApiException.conflict("VERSION_CONFLICT",
                    "尝试版本不匹配：expected=" + req.expectedVersion() + ", actual=" + attempt.version());
        }
        if (!"IN_PROGRESS".equals(attempt.status())) {
            throw ApiException.unprocessable("ILLEGAL_ATTEMPT_STATE", "仅进行中的尝试可回报清单: " + attempt.status());
        }
        Set<String> allowed = new LinkedHashSet<>(checklistCodes(ob.id()));
        for (String code : items) {
            if (!allowed.contains(code)) {
                throw ApiException.badRequest("UNKNOWN_CHECKLIST_ITEM", "检查项不属于本周期义务清单: " + code);
            }
        }
        Instant now = Instant.now(clock);
        for (String code : items) {
            jdbc.update("MERGE INTO maintenance_attempt_item(attempt_id, item_code, done_at, created_at) "
                            + "KEY(attempt_id, item_code) VALUES (?,?,?,?)",
                    attemptId, code, ElevatorService.ts(req.at()), ElevatorService.ts(now));
        }
        jdbc.update("UPDATE maintenance_attempt SET version=version+1, updated_at=? WHERE id=?",
                ElevatorService.ts(now), attemptId);
        appendEvent(ob.id(), attemptId, "ITEM_DONE", "已做检查项 " + items, now);
        return view(ob.id());
    }

    // ---------------------------------------------------------------- 完成

    public ResponseEntity<String> complete(long attemptId, ObligationCompleteRequest req) {
        List<Long> confirmers = sortedDistinct(req.confirmerIds());
        List<String> items = sortedDistinct(req.doneItems());
        String fp = Fingerprints.structured(
                List.of("scope=OBLIGATION_COMPLETE", "requestId=" + req.requestId(),
                        "attemptId=" + attemptId, "expectedVersion=" + req.expectedVersion(), "at=" + req.at()),
                List.of(confirmers.stream().map(String::valueOf).toList(), items));
        return idempotency.handle("OBLIGATION_COMPLETE", req.requestId(), fp,
                () -> doComplete(attemptId, req, confirmers, items));
    }

    private ObligationView doComplete(long attemptId, ObligationCompleteRequest req,
                                      List<Long> confirmerIds, List<String> doneItems) {
        // 统一加锁顺序：电梯 → 义务 → 尝试，与接报挂起竞争时形成确定终态
        long obligationId = readAttempt(attemptId).obligationId();
        long elevatorId = readObligation(obligationId).elevatorId();
        ElevatorRow ev = elevatorService.lockElevator(elevatorId);
        ObligationRow ob = lockObligation(obligationId);
        AttemptRow attempt = lockAttempt(attemptId);
        Instant now = Instant.now(clock);

        // 旧尝试回报：被救援挂起的尝试只记一次迟到；已迟到/已完成的重复回报直接返回当前视图
        if ("SUSPENDED".equals(attempt.status())) {
            registerLateOutcome(ob, attempt, req, ev, now);
            return view(ob.id());
        }
        if ("LATE".equals(attempt.status()) || "COMPLETED".equals(attempt.status())) {
            return view(ob.id());
        }
        if (idempotency.exists("OBLIGATION_COMPLETE", req.requestId())) {
            return view(ob.id());
        }
        if (req.expectedVersion() != attempt.version()) {
            throw ApiException.conflict("VERSION_CONFLICT",
                    "尝试版本不匹配：expected=" + req.expectedVersion() + ", actual=" + attempt.version());
        }
        if (!"IN_PROGRESS".equals(attempt.status())) {
            throw ApiException.unprocessable("ILLEGAL_ATTEMPT_STATE", "当前尝试状态不允许完成: " + attempt.status());
        }
        if (elevatorService.hasActiveRescue(ev.id())) {
            // 与再次救援竞争：救援接报已原子挂起尝试，走到这里说明挂起在先，按迟到回报处理
            registerLateOutcome(ob, attempt, req, ev, now);
            return view(ob.id());
        }
        List<Long> assigned = attemptStaff(attemptId);
        requireTwoCertifiedStaff(confirmerIds);
        if (!confirmerIds.equals(assigned)) {
            throw ApiException.unprocessable("CONFIRMERS_MISMATCH",
                    "双人共同确认人须恰为本次尝试占用的两名持证人员: assigned=" + assigned);
        }
        Set<String> required = new LinkedHashSet<>(checklistCodes(ob.id()));
        Set<String> doneUnion = new LinkedHashSet<>(doneItems(attemptId));
        doneUnion.addAll(doneItems);
        for (String code : doneItems) {
            if (!required.contains(code)) {
                throw ApiException.badRequest("UNKNOWN_CHECKLIST_ITEM", "检查项不属于本周期义务清单: " + code);
            }
        }
        if (!doneUnion.equals(required)) {
            throw ApiException.unprocessable("CHECKLIST_INCOMPLETE",
                    "新尝试须完成全部清单，缺失 " + required.stream().filter(c -> !doneUnion.contains(c)).toList());
        }
        for (String code : doneItems) {
            jdbc.update("MERGE INTO maintenance_attempt_item(attempt_id, item_code, done_at, created_at) "
                            + "KEY(attempt_id, item_code) VALUES (?,?,?,?)",
                    attemptId, code, ElevatorService.ts(req.at()), ElevatorService.ts(now));
        }

        // 登记有效维保记录、终结义务、释放资源：同一事务
        LocalDate performedDay = req.at().atZone(ZoneOffset.UTC).toLocalDate();
        LocalDate dueDay = ob.baseDay().plusDays(CYCLE_DAYS);
        boolean overdue = performedDay.isAfter(dueDay);
        MaintenanceResult result = overdue ? MaintenanceResult.OVERDUE : MaintenanceResult.COMPLIANT;
        jdbc.update("INSERT INTO maintenance_record(elevator_id, request_id, performed_day, due_day, result, note, "
                        + "obligation_id, attempt_id, created_at) VALUES (?,?,?,?,?,?,?,?,?)",
                ev.id(), req.requestId(), performedDay, dueDay, result.name(),
                "履约尝试 #" + attempt.seq() + " 双人共同确认完成", ob.id(), attemptId, ElevatorService.ts(now));
        long recordId = jdbc.queryForObject("SELECT MAX(id) FROM maintenance_record WHERE elevator_id=?",
                Long.class, ev.id());
        if (overdue) {
            // 不改原超期停梯规则：逾期完成锁定并列入重点监察；逾期续派不自动复梯
            jdbc.update("UPDATE elevator SET key_supervision=TRUE, last_maintenance_day=? WHERE id=?",
                    performedDay, ev.id());
            boolean alreadyLocked = elevatorService.openStops(ev.id()).stream()
                    .anyMatch(s -> StopReason.MAINTENANCE_OVERDUE.name().equals(s.reason()));
            if (!alreadyLocked) {
                if ("RUNNING".equals(ev.status())) {
                    elevatorService.stopIfRunning(ev, StopReason.MAINTENANCE_OVERDUE, null, req.at());
                } else {
                    elevatorService.appendChain(ev.id(), ChainAction.STOP, StopReason.MAINTENANCE_OVERDUE, null, req.at());
                }
            }
        } else {
            jdbc.update("UPDATE elevator SET last_maintenance_day=? WHERE id=?", performedDay, ev.id());
        }
        jdbc.update("UPDATE maintenance_attempt SET status='COMPLETED', version=version+1, complete_request_id=?, "
                        + "completed_at=?, updated_at=? WHERE id=?",
                req.requestId(), ElevatorService.ts(req.at()), ElevatorService.ts(now), attemptId);
        jdbc.update("UPDATE maintenance_obligation SET status='FULFILLED', active_attempt_id=NULL, version=version+1, "
                        + "fulfilled_record_id=?, fulfilled_at=?, updated_at=? WHERE id=?",
                recordId, ElevatorService.ts(req.at()), ElevatorService.ts(now), ob.id());
        jdbc.update("DELETE FROM maintenance_attempt_staff WHERE attempt_id=?", attemptId);
        appendEvent(ob.id(), attemptId, "ATTEMPT_COMPLETED", "全部清单完成并双人共同确认，维保 " + result, now);
        appendEvent(ob.id(), attemptId, "OBLIGATION_FULFILLED",
                "登记有效维保记录 id=" + recordId + "，义务终结，资源释放", now);
        return view(ob.id());
    }

    /** 旧尝试的迟到回报：尝试置 LATE、台账记一条 LATE_EVENT，义务不终结。 */
    private void registerLateOutcome(ObligationRow ob, AttemptRow attempt, ObligationCompleteRequest req,
                                     ElevatorRow ev, Instant now) {
        LocalDate performedDay = req.at().atZone(ZoneOffset.UTC).toLocalDate();
        LocalDate dueDay = ob.baseDay().plusDays(CYCLE_DAYS);
        jdbc.update("INSERT INTO maintenance_record(elevator_id, request_id, performed_day, due_day, result, note, "
                        + "obligation_id, attempt_id, created_at) VALUES (?,?,?,?,'LATE_EVENT',?, ?,?,?)",
                ev.id(), req.requestId(), performedDay, dueDay,
                "中断的旧尝试迟到回报，不算本周期维保完成", ob.id(), attempt.id(), ElevatorService.ts(now));
        jdbc.update("UPDATE maintenance_attempt SET status='LATE', version=version+1, complete_request_id=?, "
                        + "completed_at=?, suspend_rescue_id=COALESCE(suspend_rescue_id, ?), updated_at=? WHERE id=?",
                req.requestId(), ElevatorService.ts(req.at()), attempt.suspendRescueId(),
                ElevatorService.ts(now), attempt.id());
        appendEvent(ob.id(), attempt.id(), "ATTEMPT_LATE_REPORT",
                "旧尝试迟到回报，仅记迟到事件，本周期义务仍须续派完成", now);
    }

    // ---------------------------------------------------------------- 救援挂起 / 手工结清

    /**
     * 接报救援时调用（与接报同事务）：原子挂起该电梯全部未完成履约尝试，
     * 释放占用人员，保留已做清单；义务回到可续派状态，到期义务不变。
     */
    public void suspendActiveForRescue(long elevatorId, long rescueId, Instant at) {
        List<Long> active = jdbc.query(
                "SELECT id FROM maintenance_attempt WHERE status IN ('ALLOCATED','IN_PROGRESS') "
                        + "AND obligation_id IN (SELECT id FROM maintenance_obligation WHERE elevator_id=?)",
                (rs, i) -> rs.getLong(1), elevatorId);
        for (Long attemptId : active) {
            AttemptRow attempt = lockAttempt(attemptId);
            jdbc.update("UPDATE maintenance_attempt SET status='SUSPENDED', version=version+1, suspended_at=?, "
                            + "suspend_rescue_id=?, updated_at=? WHERE id=?",
                    ElevatorService.ts(at), rescueId, ElevatorService.ts(at), attemptId);
            jdbc.update("DELETE FROM maintenance_attempt_staff WHERE attempt_id=?", attemptId);
            jdbc.update("UPDATE maintenance_obligation SET status='OPEN', active_attempt_id=NULL, version=version+1, "
                            + "updated_at=? WHERE id=?",
                    ElevatorService.ts(at), attempt.obligationId());
            appendEvent(attempt.obligationId(), attemptId, "ATTEMPT_SUSPENDED",
                    "接报救援单 id=" + rescueId + "，原子挂起未完成尝试并释放人员，已做清单保留", at);
        }
    }

    /**
     * 旧手工维保登记有效记录（COMPLIANT/OVERDUE）时调用（与登记同事务）：
     * 将该电梯最早的未完成义务一次性结清，释放其尝试占用的人员资源。
     */
    public void settleByManualRecord(long elevatorId, long recordId, LocalDate performedDay, Instant at) {
        List<Long> openIds = jdbc.query(
                "SELECT id FROM maintenance_obligation WHERE elevator_id=? AND status<>'FULFILLED' ORDER BY period_index",
                (rs, i) -> rs.getLong(1), elevatorId);
        if (openIds.isEmpty()) {
            return;
        }
        long obligationId = openIds.get(0);
        List<Long> active = jdbc.query(
                "SELECT id FROM maintenance_attempt WHERE obligation_id=? AND status IN ('ALLOCATED','IN_PROGRESS')",
                (rs, i) -> rs.getLong(1), obligationId);
        for (Long attemptId : active) {
            jdbc.update("UPDATE maintenance_attempt SET status='LATE', version=version+1, completed_at=?, updated_at=? "
                            + "WHERE id=?",
                    ElevatorService.ts(at), ElevatorService.ts(at), attemptId);
            jdbc.update("DELETE FROM maintenance_attempt_staff WHERE attempt_id=?", attemptId);
            appendEvent(obligationId, attemptId, "ATTEMPT_LATE_REPORT",
                    "手工维保入口登记有效记录，进行中尝试不再作为履约完成", at);
        }
        jdbc.update("UPDATE maintenance_obligation SET status='FULFILLED', active_attempt_id=NULL, version=version+1, "
                        + "fulfilled_record_id=?, fulfilled_at=?, updated_at=? WHERE id=?",
                recordId, ElevatorService.ts(at), ElevatorService.ts(at), obligationId);
        appendEvent(obligationId, null, "OBLIGATION_SETTLED",
                "手工维保有效记录 id=" + recordId + "（执行日 " + performedDay + "）一次性结清未完成义务", at);
    }

    // ---------------------------------------------------------------- 查询

    @Transactional(readOnly = true)
    public ObligationView getObligation(long obligationId) {
        return view(obligationId);
    }

    @Transactional(readOnly = true)
    public List<ObligationView> listForElevator(long elevatorId) {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM elevator WHERE id=?", Integer.class, elevatorId);
        if (n == null || n == 0) {
            throw ApiException.notFound("电梯不存在: " + elevatorId);
        }
        List<Long> ids = jdbc.query("SELECT id FROM maintenance_obligation WHERE elevator_id=? ORDER BY period_index",
                (rs, i) -> rs.getLong(1), elevatorId);
        return ids.stream().map(this::view).toList();
    }

    @Transactional(readOnly = true)
    public List<ObligationEventView> events(long obligationId) {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM maintenance_obligation WHERE id=?",
                Integer.class, obligationId);
        if (n == null || n == 0) {
            throw ApiException.notFound("维保义务不存在: " + obligationId);
        }
        return jdbc.query("SELECT * FROM maintenance_obligation_event WHERE obligation_id=? ORDER BY id",
                (rs, i) -> new ObligationEventView(rs.getLong("id"),
                        rs.getObject("attempt_id", Long.class), rs.getString("event"), rs.getString("detail"),
                        rs.getObject("created_at", LocalDateTime.class).toInstant(ZoneOffset.UTC)),
                obligationId);
    }

    // ---------------------------------------------------------------- 内部

    private record ValidBase(int validCount, LocalDate baseDay, Long lastRecordId) {
    }

    /** 有效维保记录数与下一周期基准：COMPLIANT/OVERDUE 为有效，LATE_EVENT 不算。 */
    private ValidBase currentValidBase(ElevatorRow ev) {
        return jdbc.query(
                "SELECT id, performed_day FROM maintenance_record WHERE elevator_id=? AND result IN ('COMPLIANT','OVERDUE') "
                        + "ORDER BY id",
                rs -> {
                    int count = 0;
                    LocalDate lastDay = null;
                    Long lastId = null;
                    while (rs.next()) {
                        count++;
                        lastId = rs.getLong(1);
                        lastDay = rs.getObject(2, LocalDate.class);
                    }
                    LocalDate base = lastDay != null ? lastDay : ev.commissionedDay();
                    return new ValidBase(count, base, lastId);
                }, ev.id());
    }

    private static Instant dueAtFor(LocalDate baseDay) {
        return LocalDateTime.of(baseDay.plusDays(CYCLE_DAYS), LocalTime.MIDNIGHT).toInstant(ZoneOffset.UTC);
    }

    private ObligationRow lockObligation(long id) {
        List<ObligationRow> rows = jdbc.query(
                "SELECT * FROM maintenance_obligation WHERE id=? FOR UPDATE", this::mapObligation, id);
        if (rows.isEmpty()) {
            throw ApiException.notFound("维保义务不存在: " + id);
        }
        return rows.get(0);
    }

    private ObligationRow readObligation(long id) {
        List<ObligationRow> rows = jdbc.query(
                "SELECT * FROM maintenance_obligation WHERE id=?", this::mapObligation, id);
        if (rows.isEmpty()) {
            throw ApiException.notFound("维保义务不存在: " + id);
        }
        return rows.get(0);
    }

    private AttemptRow lockAttempt(long id) {
        List<AttemptRow> rows = jdbc.query(
                "SELECT * FROM maintenance_attempt WHERE id=? FOR UPDATE", this::mapAttempt, id);
        if (rows.isEmpty()) {
            throw ApiException.notFound("履约尝试不存在: " + id);
        }
        return rows.get(0);
    }

    private AttemptRow readAttempt(long id) {
        List<AttemptRow> rows = jdbc.query(
                "SELECT * FROM maintenance_attempt WHERE id=?", this::mapAttempt, id);
        if (rows.isEmpty()) {
            throw ApiException.notFound("履约尝试不存在: " + id);
        }
        return rows.get(0);
    }

    private ObligationRow mapObligation(java.sql.ResultSet rs, int i) throws java.sql.SQLException {
        return new ObligationRow(rs.getLong("id"), rs.getLong("elevator_id"), rs.getInt("period_index"),
                rs.getObject("base_day", LocalDate.class),
                rs.getObject("due_at", LocalDateTime.class).toInstant(ZoneOffset.UTC),
                rs.getString("status"), rs.getInt("version"), rs.getObject("active_attempt_id", Long.class),
                rs.getObject("last_valid_record_id", Long.class), rs.getObject("fulfilled_record_id", Long.class),
                toInstant(rs, "fulfilled_at"));
    }

    private AttemptRow mapAttempt(java.sql.ResultSet rs, int i) throws java.sql.SQLException {
        return new AttemptRow(rs.getLong("id"), rs.getLong("obligation_id"), rs.getInt("seq"),
                rs.getString("status"), rs.getInt("version"), rs.getString("allocate_request_id"),
                rs.getString("start_request_id"), rs.getString("complete_request_id"),
                toInstant(rs, "allocated_at"), toInstant(rs, "started_at"),
                toInstant(rs, "suspended_at"), toInstant(rs, "completed_at"),
                rs.getObject("suspend_rescue_id", Long.class));
    }

    private static Instant toInstant(java.sql.ResultSet rs, String col) throws java.sql.SQLException {
        LocalDateTime v = rs.getObject(col, LocalDateTime.class);
        return v == null ? null : v.toInstant(ZoneOffset.UTC);
    }

    private List<Long> attemptStaff(long attemptId) {
        return jdbc.query(
                "SELECT staff_id FROM maintenance_attempt_staff WHERE attempt_id=? ORDER BY staff_id",
                (rs, i) -> rs.getLong(1), attemptId);
    }

    private List<String> checklistCodes(long obligationId) {
        return jdbc.query(
                "SELECT item_code FROM maintenance_obligation_checklist WHERE obligation_id=? ORDER BY item_code",
                (rs, i) -> rs.getString(1), obligationId);
    }

    private List<String> doneItems(long attemptId) {
        return jdbc.query(
                "SELECT item_code FROM maintenance_attempt_item WHERE attempt_id=? ORDER BY item_code",
                (rs, i) -> rs.getString(1), attemptId);
    }

    /**
     * 恰两名、去重、持有效证书的人员；不存在 404，人数/证书不符 422。
     * 先按固定顺序锁定人员行：跨电梯并发占用同一人员时在此排队，后到者可见已提交的作业窗。
     */
    private void requireTwoCertifiedStaff(List<Long> staffIds) {
        if (staffIds.size() != 2) {
            throw ApiException.unprocessable("TWO_STAFF_REQUIRED", "履约须恰两名不同的持证人员: " + staffIds);
        }
        List<Boolean> certified = jdbc.query(
                "SELECT certified FROM staff WHERE id IN (?,?) ORDER BY id FOR UPDATE",
                (rs, i) -> rs.getBoolean(1), staffIds.get(0), staffIds.get(1));
        if (certified.size() != 2) {
            for (Long staffId : staffIds) {
                Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM staff WHERE id=?", Integer.class, staffId);
                if (n == null || n == 0) {
                    throw ApiException.notFound("人员不存在: " + staffId);
                }
            }
        }
        for (boolean ok : certified) {
            if (!ok) {
                throw ApiException.unprocessable("UNQUALIFIED_STAFF", "存在未持有效证书的履约人员: " + staffIds);
            }
        }
    }

    /** 半开作业窗占用检查：与任何进行中尝试的同窗重叠即整批拒绝（不先占资源）。 */
    private void requireWindowsFree(List<Long> staffIds, Instant start, Instant end) {
        Integer overlaps = jdbc.queryForObject(
                "SELECT COUNT(*) FROM maintenance_attempt_staff w "
                        + "JOIN maintenance_attempt a ON a.id = w.attempt_id "
                        + "WHERE w.staff_id IN (?,?) AND a.status IN ('ALLOCATED','IN_PROGRESS') "
                        + "AND w.window_start < ? AND w.window_end > ?",
                Integer.class, staffIds.get(0), staffIds.get(1),
                ElevatorService.ts(end), ElevatorService.ts(start));
        if (overlaps != null && overlaps > 0) {
            throw ApiException.unprocessable("STAFF_WINDOW_CONFLICT",
                    "人员半开作业窗与进行中尝试重叠，整批不占资源: " + staffIds + " [" + start + "," + end + ")");
        }
    }

    private <T extends Comparable<? super T>> List<T> sortedDistinct(List<T> values) {
        return values.stream().sorted().distinct().toList();
    }

    private void appendEvent(long obligationId, Long attemptId, String event, String detail, Instant at) {
        jdbc.update("INSERT INTO maintenance_obligation_event(obligation_id, attempt_id, event, detail, created_at) "
                        + "VALUES (?,?,?,?,?)",
                obligationId, attemptId, event, detail, ElevatorService.ts(at));
    }

    private ObligationView view(long obligationId) {
        List<ObligationRow> rows = jdbc.query(
                "SELECT * FROM maintenance_obligation WHERE id=?", this::mapObligation, obligationId);
        if (rows.isEmpty()) {
            throw ApiException.notFound("维保义务不存在: " + obligationId);
        }
        ObligationRow ob = rows.get(0);
        List<String> checklist = checklistCodes(ob.id());
        List<ObligationAttemptView> attempts = new ArrayList<>();
        List<AttemptRow> attemptRows = jdbc.query(
                "SELECT * FROM maintenance_attempt WHERE obligation_id=? ORDER BY seq", this::mapAttempt, ob.id());
        for (AttemptRow a : attemptRows) {
            List<Long> staffIds = attemptStaff(a.id());
            Instant wStart = null;
            Instant wEnd = null;
            List<Instant[]> window = jdbc.query(
                    "SELECT window_start, window_end FROM maintenance_attempt_staff WHERE attempt_id=? FETCH FIRST 1 ROW ONLY",
                    (rs, i) -> {
                        LocalDateTime s = rs.getObject(1, LocalDateTime.class);
                        LocalDateTime e = rs.getObject(2, LocalDateTime.class);
                        return new Instant[]{
                                s == null ? null : s.toInstant(ZoneOffset.UTC),
                                e == null ? null : e.toInstant(ZoneOffset.UTC)};
                    },
                    a.id());
            if (!window.isEmpty()) {
                wStart = window.get(0)[0];
                wEnd = window.get(0)[1];
            }
            attempts.add(new ObligationAttemptView(a.id(), a.seq(), a.status(), a.version(),
                    a.allocateRequestId(), a.startRequestId(), a.completeRequestId(),
                    a.allocatedAt(), a.startedAt(), a.suspendedAt(), a.completedAt(), a.suspendRescueId(),
                    staffIds, wStart, wEnd, doneItems(a.id())));
        }
        return new ObligationView(ob.id(), ob.elevatorId(), ob.periodIndex(), ob.baseDay(), ob.dueAt(),
                ob.status(), ob.version(), ob.activeAttemptId(), ob.lastValidRecordId(),
                ob.fulfilledRecordId(), ob.fulfilledAt(), checklist, attempts);
    }

    private static String checklistName(String code) {
        return switch (code) {
            case "CHK_CAR_DOOR" -> "轿门与门锁";
            case "CHK_EMERGENCY_ALARM" -> "紧急报警装置";
            case "CHK_TRACTION_BRAKE" -> "曳引与制动器";
            case "CHK_LANDING_LOCK" -> "层门锁紧装置";
            default -> code;
        };
    }
}
