package com.example.starter.service;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.stream.Collectors;

import com.example.starter.domain.AttemptStatus;
import com.example.starter.domain.ObligationStatus;
import com.example.starter.error.ApiException;
import com.example.starter.service.ElevatorService.ElevatorRow;
import com.example.starter.web.dto.ApiDtos.AttemptDetailView;
import com.example.starter.web.dto.ApiDtos.AttemptDispatchRequest;
import com.example.starter.web.dto.ApiDtos.AttemptStaffView;
import com.example.starter.web.dto.ApiDtos.AttemptView;
import com.example.starter.web.dto.ApiDtos.ChecklistItemView;
import com.example.starter.web.dto.ApiDtos.ChecklistRequest;
import com.example.starter.web.dto.ApiDtos.ConfirmRequest;
import com.example.starter.web.dto.ApiDtos.ObligationView;
import com.example.starter.web.dto.ApiDtos.PlanScanRequest;
import com.example.starter.web.dto.ApiDtos.ScanResultView;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 周期维保计划编排：扫描生成履约义务（同一电梯同一到期周期唯一），
 * 派发尝试须两名持证人员与半开作业窗；救援抢占挂起后可续派新尝试，
 * 新尝试完成全部清单并双人共同确认才登记有效维保记录。
 * 行锁顺序统一为：电梯 → 义务 → 尝试 → 人员，避免死锁。
 */
@Service
public class MaintenancePlanService {

    /** 到期前可派发窗口：24 小时，恰在起点允许 */
    static final long DISPATCH_AHEAD_HOURS = 24;
    /** 合成清单项（派发时生成） */
    static final List<String> SYNTHETIC_CHECKLIST = List.of("SYN-CHECK-1", "SYN-CHECK-2", "SYN-CHECK-3");

    /** 义务行 */
    record ObligationRow(long id, long elevatorId, Long baseMaintenanceId, Instant dueAt,
                         String status, long version) {
    }

    /** 尝试行 */
    record AttemptRow(long id, long obligationId, int attemptNo, String status, long version) {
    }

    static final RowMapper<ObligationRow> OBLIGATION_MAPPER = (rs, i) -> new ObligationRow(
            rs.getLong("id"), rs.getLong("elevator_id"), rs.getObject("base_maintenance_id", Long.class),
            rs.getObject("due_at", LocalDateTime.class).toInstant(ZoneOffset.UTC),
            rs.getString("status"), rs.getLong("version"));

    private final JdbcTemplate jdbc;
    private final ElevatorService elevatorService;
    private final MaintenanceService maintenanceService;
    private final IdempotencyService idempotency;
    private final Clock clock;

    public MaintenancePlanService(JdbcTemplate jdbc, ElevatorService elevatorService,
                                  MaintenanceService maintenanceService, IdempotencyService idempotency,
                                  Clock clock) {
        this.jdbc = jdbc;
        this.elevatorService = elevatorService;
        this.maintenanceService = maintenanceService;
        this.idempotency = idempotency;
        this.clock = clock;
    }

    // ---------------------------------------------------------------- 扫描义务

    public ResponseEntity<String> scan(PlanScanRequest req) {
        String fp = Fingerprints.structured("PLAN_SCAN", req.requestId(), String.valueOf(req.elevatorId()),
                String.valueOf(req.now()));
        return idempotency.handle("PLAN_SCAN", req.requestId(), fp, () -> doScan(req));
    }

    private ScanResultView doScan(PlanScanRequest req) {
        ElevatorRow ev = elevatorService.lockElevator(req.elevatorId());
        Instant now = req.now() != null ? req.now() : Instant.now(clock);
        LocalDate base = ev.lastMaintenanceDay() != null ? ev.lastMaintenanceDay() : ev.commissionedDay();
        Instant dueAt = base.plusDays(MaintenanceService.CYCLE_DAYS).atStartOfDay().toInstant(ZoneOffset.UTC);
        // 同一电梯同一到期周期只一项义务：重复扫描不多派（电梯行锁使并发扫描串行）
        List<ObligationRow> existing = jdbc.query(
                "SELECT * FROM maintenance_obligation WHERE elevator_id=? AND due_at=?",
                OBLIGATION_MAPPER, ev.id(), ElevatorService.ts(dueAt));
        if (!existing.isEmpty()) {
            ObligationRow o = existing.get(0);
            return new ScanResultView(o.id(), ev.id(), dueAt, o.status(), false, o.version());
        }
        // 到期前 24 小时（含恰在起点）才创建义务
        if (now.isBefore(dueAt.minus(DISPATCH_AHEAD_HOURS, ChronoUnit.HOURS))) {
            return new ScanResultView(null, ev.id(), dueAt, null, false, null);
        }
        Long baseMaintenanceId = ev.lastMaintenanceDay() == null ? null : jdbc.query(
                "SELECT id FROM maintenance_record WHERE elevator_id=? AND result IN ('COMPLIANT','OVERDUE') "
                        + "AND performed_day=? ORDER BY id DESC LIMIT 1",
                (rs, i) -> rs.getLong(1), ev.id(), ev.lastMaintenanceDay()).stream().findFirst().orElse(null);
        Instant createdAt = Instant.now(clock);
        jdbc.update("INSERT INTO maintenance_obligation(elevator_id, base_maintenance_id, due_at, status, "
                        + "version, created_at) VALUES (?,?,?,'OPEN',1,?)",
                ev.id(), baseMaintenanceId, ElevatorService.ts(dueAt), ElevatorService.ts(createdAt));
        long id = jdbc.queryForObject(
                "SELECT id FROM maintenance_obligation WHERE elevator_id=? AND due_at=?",
                Long.class, ev.id(), ElevatorService.ts(dueAt));
        return new ScanResultView(id, ev.id(), dueAt, ObligationStatus.OPEN.name(), true, 1L);
    }

    // ---------------------------------------------------------------- 派发/续派尝试

    public ResponseEntity<String> dispatchAttempt(long obligationId, AttemptDispatchRequest req) {
        List<Long> staffIds = req.staffIds().stream().distinct().sorted().toList();
        if (staffIds.size() != 2) {
            throw ApiException.badRequest("TWO_STAFF_REQUIRED", "维保任务须恰两名持证人员");
        }
        if (!req.windowStart().isBefore(req.windowEnd())) {
            throw ApiException.badRequest("INVALID_WINDOW", "作业窗起点须早于终点（半开窗 [start, end)）");
        }
        String fp = Fingerprints.structured("PLAN_DISPATCH", req.requestId(), String.valueOf(obligationId),
                String.valueOf(req.expectedVersion()),
                staffIds.stream().map(String::valueOf).collect(Collectors.joining(",")),
                req.windowStart().toString(), req.windowEnd().toString());
        return idempotency.handle("PLAN_DISPATCH", req.requestId(), fp,
                () -> doDispatch(obligationId, req, staffIds));
    }

    private AttemptView doDispatch(long obligationId, AttemptDispatchRequest req, List<Long> staffIds) {
        ObligationRow ob = lockObligationWithElevator(obligationId);
        if (!ObligationStatus.OPEN.name().equals(ob.status())) {
            throw ApiException.unprocessable("OBLIGATION_CLOSED", "履约义务已结清，不得再派发尝试");
        }
        if (ob.version() != req.expectedVersion()) {
            throw ApiException.conflict("VERSION_CONFLICT",
                    "义务版本冲突: expected=" + req.expectedVersion() + ", actual=" + ob.version());
        }
        Instant now = Instant.now(clock);
        if (now.isBefore(ob.dueAt().minus(DISPATCH_AHEAD_HOURS, ChronoUnit.HOURS))) {
            throw ApiException.unprocessable("PREMATURE_DISPATCH", "尚未进入派发窗口（到期前24小时，恰在起点允许）");
        }
        Integer inFlight = jdbc.queryForObject(
                "SELECT COUNT(*) FROM maintenance_attempt WHERE obligation_id=? AND status='DISPATCHED'",
                Integer.class, obligationId);
        if (inFlight != null && inFlight > 0) {
            throw ApiException.unprocessable("ATTEMPT_IN_FLIGHT", "该义务已存在进行中的尝试");
        }
        if (elevatorService.hasActiveRescue(ob.elevatorId())) {
            throw ApiException.unprocessable("RESCUE_IN_FLIGHT", "该电梯存在进行中救援，不得派发维保尝试");
        }
        // 开始时重查证书有效；人员行按 ID 升序加锁，保证并发派发串行
        List<Long> locked = jdbc.query(
                "SELECT id FROM staff WHERE id IN (?,?) ORDER BY id FOR UPDATE",
                (rs, i) -> rs.getLong(1), staffIds.get(0), staffIds.get(1));
        for (Long staffId : staffIds) {
            if (!locked.contains(staffId)) {
                throw ApiException.notFound("人员不存在: " + staffId);
            }
            Boolean certified = jdbc.queryForObject("SELECT certified FROM staff WHERE id=?",
                    Boolean.class, staffId);
            if (certified == null || !certified) {
                throw ApiException.unprocessable("UNQUALIFIED_STAFF", "维保人员未持有效证书: " + staffId);
            }
        }
        // 半开作业窗不可重叠；任一不足整批不占资源（本事务回滚即整批不占）
        for (Long staffId : staffIds) {
            Integer overlap = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM maintenance_attempt a JOIN maintenance_attempt_staff s "
                            + "ON s.attempt_id=a.id WHERE a.status='DISPATCHED' AND s.staff_id=? "
                            + "AND a.window_start < ? AND ? < a.window_end",
                    Integer.class, staffId, ElevatorService.ts(req.windowEnd()),
                    ElevatorService.ts(req.windowStart()));
            if (overlap != null && overlap > 0) {
                throw ApiException.unprocessable("STAFF_WINDOW_OVERLAP",
                        "人员在重叠作业窗内已有维保任务，整批不占资源: " + staffId);
            }
        }
        Integer seq = jdbc.queryForObject(
                "SELECT COUNT(*) FROM maintenance_attempt WHERE obligation_id=?", Integer.class, obligationId);
        Instant createdAt = Instant.now(clock);
        jdbc.update("INSERT INTO maintenance_attempt(obligation_id, attempt_no, status, window_start, window_end, "
                        + "version, created_at) VALUES (?,?,'DISPATCHED',?,?,1,?)",
                obligationId, (seq == null ? 0 : seq) + 1, ElevatorService.ts(req.windowStart()),
                ElevatorService.ts(req.windowEnd()), ElevatorService.ts(createdAt));
        long attemptId = jdbc.queryForObject("SELECT MAX(id) FROM maintenance_attempt WHERE obligation_id=?",
                Long.class, obligationId);
        for (Long staffId : staffIds) {
            jdbc.update("INSERT INTO maintenance_attempt_staff(attempt_id, staff_id) VALUES (?,?)",
                    attemptId, staffId);
        }
        for (String item : SYNTHETIC_CHECKLIST) {
            jdbc.update("INSERT INTO maintenance_checklist_item(attempt_id, item) VALUES (?,?)", attemptId, item);
        }
        jdbc.update("UPDATE maintenance_obligation SET version=version+1 WHERE id=?", obligationId);
        return attemptView(attemptId);
    }

    // ---------------------------------------------------------------- 清单回报

    public ResponseEntity<String> reportChecklist(long attemptId, ChecklistRequest req) {
        String fp = Fingerprints.structured("PLAN_CHECKLIST", req.requestId(), String.valueOf(attemptId),
                String.valueOf(req.expectedVersion()), req.item(), String.valueOf(req.doneAt()));
        return idempotency.handle("PLAN_CHECKLIST", req.requestId(), fp, () -> doChecklist(attemptId, req));
    }

    private AttemptView doChecklist(long attemptId, ChecklistRequest req) {
        AttemptRow at = lockAttemptWithElevator(attemptId);
        // 状态机优先：非进行中直接拒绝（挂起由系统侧发起，回报方持有的版本已落后）
        if (!AttemptStatus.DISPATCHED.name().equals(at.status())) {
            throw ApiException.unprocessable("ATTEMPT_NOT_ACTIVE",
                    "尝试非进行中状态，清单回报被拒绝: " + at.status());
        }
        if (at.version() != req.expectedVersion()) {
            throw ApiException.conflict("VERSION_CONFLICT",
                    "尝试版本冲突: expected=" + req.expectedVersion() + ", actual=" + at.version());
        }
        List<Long> items = jdbc.query(
                "SELECT id FROM maintenance_checklist_item WHERE attempt_id=? AND item=?",
                (rs, i) -> rs.getLong(1), attemptId, req.item());
        if (items.isEmpty()) {
            throw ApiException.unprocessable("UNKNOWN_CHECKLIST_ITEM", "清单项不属于该尝试: " + req.item());
        }
        Instant doneAt = req.doneAt() != null ? req.doneAt() : Instant.now(clock);
        Boolean done = jdbc.queryForObject(
                "SELECT done FROM maintenance_checklist_item WHERE id=?", Boolean.class, items.get(0));
        if (done == null || !done) {
            jdbc.update("UPDATE maintenance_checklist_item SET done=TRUE, done_at=? WHERE id=?",
                    ElevatorService.ts(doneAt), items.get(0));
            jdbc.update("UPDATE maintenance_attempt SET version=version+1 WHERE id=?", attemptId);
        }
        maybeComplete(attemptId, at.obligationId(), doneAt);
        return attemptView(attemptId);
    }

    // ---------------------------------------------------------------- 完成确认

    public ResponseEntity<String> confirm(long attemptId, ConfirmRequest req) {
        String fp = Fingerprints.structured("PLAN_CONFIRM", req.requestId(), String.valueOf(attemptId),
                String.valueOf(req.expectedVersion()), String.valueOf(req.staffId()),
                String.valueOf(req.confirmedAt()));
        return idempotency.handle("PLAN_CONFIRM", req.requestId(), fp, () -> doConfirm(attemptId, req));
    }

    private AttemptView doConfirm(long attemptId, ConfirmRequest req) {
        AttemptRow at = lockAttemptWithElevator(attemptId);
        Instant confirmedAt = req.confirmedAt() != null ? req.confirmedAt() : Instant.now(clock);
        // 旧尝试（挂起/被取代）的回报只记迟到事件，不当本周期维保完成；
        // 挂起由系统侧发起，回报方持有的版本已落后，故此分支不校验版本
        if (AttemptStatus.SUSPENDED.name().equals(at.status())
                || AttemptStatus.SUPERSEDED.name().equals(at.status())) {
            long elevatorId = jdbc.queryForObject(
                    "SELECT elevator_id FROM maintenance_obligation WHERE id=?", Long.class, at.obligationId());
            jdbc.update("INSERT INTO maintenance_record(elevator_id, request_id, performed_day, due_day, result, "
                            + "note) VALUES (?,?,?,NULL,'LATE_EVENT',?)",
                    elevatorId, req.requestId(), confirmedAt.atZone(ZoneOffset.UTC).toLocalDate(),
                    "旧尝试回报仅记迟到事件，不算本周期维保完成");
            jdbc.update("UPDATE maintenance_attempt SET status='LATE_CLOSED', version=version+1 WHERE id=?",
                    attemptId);
            return attemptView(attemptId);
        }
        if (!AttemptStatus.DISPATCHED.name().equals(at.status())) {
            throw ApiException.unprocessable("ILLEGAL_STATE", "当前状态不允许确认: " + at.status());
        }
        if (at.version() != req.expectedVersion()) {
            throw ApiException.conflict("VERSION_CONFLICT",
                    "尝试版本冲突: expected=" + req.expectedVersion() + ", actual=" + at.version());
        }
        List<Boolean> confirmed = jdbc.query(
                "SELECT confirmed FROM maintenance_attempt_staff WHERE attempt_id=? AND staff_id=?",
                (rs, i) -> rs.getBoolean(1), attemptId, req.staffId());
        if (confirmed.isEmpty()) {
            throw ApiException.unprocessable("NOT_ATTEMPT_STAFF", "该人员不属于本尝试: " + req.staffId());
        }
        if (!confirmed.get(0)) {
            jdbc.update("UPDATE maintenance_attempt_staff SET confirmed=TRUE, confirmed_at=? "
                            + "WHERE attempt_id=? AND staff_id=?",
                    ElevatorService.ts(confirmedAt), attemptId, req.staffId());
            jdbc.update("UPDATE maintenance_attempt SET version=version+1 WHERE id=?", attemptId);
        }
        maybeComplete(attemptId, at.obligationId(), confirmedAt);
        return attemptView(attemptId);
    }

    /**
     * 完成条件满足（全部清单完成且双人共同确认）时，在同一事务内：
     * 登记有效维保记录、终结义务、关闭尝试并释放人员。逾期完成不自动复梯。
     */
    private void maybeComplete(long attemptId, long obligationId, Instant at) {
        Integer undone = jdbc.queryForObject(
                "SELECT COUNT(*) FROM maintenance_checklist_item WHERE attempt_id=? AND done=FALSE",
                Integer.class, attemptId);
        Integer unconfirmed = jdbc.queryForObject(
                "SELECT COUNT(*) FROM maintenance_attempt_staff WHERE attempt_id=? AND confirmed=FALSE",
                Integer.class, attemptId);
        if ((undone != null && undone > 0) || (unconfirmed != null && unconfirmed > 0)) {
            return;
        }
        ObligationRow ob = lockObligation(obligationId);
        if (elevatorService.hasActiveRescue(ob.elevatorId())) {
            // 与再次救援竞争：接报已挂起尝试，此处为防御性一致校验
            throw ApiException.unprocessable("RESCUE_IN_FLIGHT", "存在进行中救援，不得完成维保登记");
        }
        ElevatorRow ev = elevatorService.lockElevator(ob.elevatorId());
        LocalDate performedDay = at.atZone(ZoneOffset.UTC).toLocalDate();
        maintenanceService.applyEffectiveRecord(ev, "PLAN-ATTEMPT-" + attemptId, performedDay,
                "计划尝试 " + attemptId + " 双人确认完成");
        jdbc.update("UPDATE maintenance_obligation SET status='FULFILLED', fulfilled_at=?, version=version+1 "
                + "WHERE id=?", ElevatorService.ts(at), obligationId);
        jdbc.update("UPDATE maintenance_attempt SET status='COMPLETED', completed_at=?, version=version+1 "
                + "WHERE id=?", ElevatorService.ts(at), attemptId);
    }

    // ---------------------------------------------------------------- 查询

    @Transactional(readOnly = true)
    public List<ObligationView> obligations(Long elevatorId) {
        String sql = "SELECT * FROM maintenance_obligation"
                + (elevatorId != null ? " WHERE elevator_id=?" : "") + " ORDER BY id";
        List<ObligationRow> rows = elevatorId != null
                ? jdbc.query(sql, OBLIGATION_MAPPER, elevatorId) : jdbc.query(sql, OBLIGATION_MAPPER);
        return rows.stream().map(this::toObligationView).toList();
    }

    @Transactional(readOnly = true)
    public ObligationView obligation(long id) {
        List<ObligationRow> rows = jdbc.query("SELECT * FROM maintenance_obligation WHERE id=?",
                OBLIGATION_MAPPER, id);
        if (rows.isEmpty()) {
            throw ApiException.notFound("维保义务不存在: " + id);
        }
        return toObligationView(rows.get(0));
    }

    @Transactional(readOnly = true)
    public List<AttemptView> attempts(long obligationId) {
        obligation(obligationId);
        return jdbc.query("SELECT id FROM maintenance_attempt WHERE obligation_id=? ORDER BY id",
                (rs, i) -> attemptView(rs.getLong(1)), obligationId);
    }

    @Transactional(readOnly = true)
    public AttemptDetailView attemptDetail(long attemptId) {
        AttemptView view = attemptViewChecked(attemptId);
        List<AttemptStaffView> staff = jdbc.query(
                "SELECT staff_id, confirmed, confirmed_at FROM maintenance_attempt_staff "
                        + "WHERE attempt_id=? ORDER BY staff_id",
                (rs, i) -> new AttemptStaffView(rs.getLong(1), rs.getBoolean(2), toInstant(rs, 3)), attemptId);
        List<ChecklistItemView> checklist = jdbc.query(
                "SELECT item, done, done_at FROM maintenance_checklist_item WHERE attempt_id=? ORDER BY id",
                (rs, i) -> new ChecklistItemView(rs.getString(1), rs.getBoolean(2), toInstant(rs, 3)), attemptId);
        return new AttemptDetailView(view, staff, checklist);
    }

    // ---------------------------------------------------------------- 内部

    /** 先锁电梯行再锁义务行（统一锁顺序），义务不存在 404。 */
    private ObligationRow lockObligationWithElevator(long obligationId) {
        List<ObligationRow> rows = jdbc.query("SELECT * FROM maintenance_obligation WHERE id=?",
                OBLIGATION_MAPPER, obligationId);
        if (rows.isEmpty()) {
            throw ApiException.notFound("维保义务不存在: " + obligationId);
        }
        elevatorService.lockElevator(rows.get(0).elevatorId());
        return lockObligation(obligationId);
    }

    private ObligationRow lockObligation(long obligationId) {
        List<ObligationRow> rows = jdbc.query("SELECT * FROM maintenance_obligation WHERE id=? FOR UPDATE",
                OBLIGATION_MAPPER, obligationId);
        if (rows.isEmpty()) {
            throw ApiException.notFound("维保义务不存在: " + obligationId);
        }
        return rows.get(0);
    }

    /** 先锁电梯行再锁尝试行（统一锁顺序），尝试不存在 404。 */
    private AttemptRow lockAttemptWithElevator(long attemptId) {
        List<AttemptRow> rows = jdbc.query("SELECT * FROM maintenance_attempt WHERE id=?",
                attemptRowMapper(), attemptId);
        if (rows.isEmpty()) {
            throw ApiException.notFound("维保尝试不存在: " + attemptId);
        }
        long elevatorId = jdbc.queryForObject(
                "SELECT elevator_id FROM maintenance_obligation WHERE id=?", Long.class, rows.get(0).obligationId());
        elevatorService.lockElevator(elevatorId);
        List<AttemptRow> locked = jdbc.query("SELECT * FROM maintenance_attempt WHERE id=? FOR UPDATE",
                attemptRowMapper(), attemptId);
        return locked.get(0);
    }

    private static RowMapper<AttemptRow> attemptRowMapper() {
        return (rs, i) -> new AttemptRow(rs.getLong("id"), rs.getLong("obligation_id"),
                rs.getInt("attempt_no"), rs.getString("status"), rs.getLong("version"));
    }

    private AttemptView attemptViewChecked(long attemptId) {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM maintenance_attempt WHERE id=?",
                Integer.class, attemptId);
        if (n == null || n == 0) {
            throw ApiException.notFound("维保尝试不存在: " + attemptId);
        }
        return attemptView(attemptId);
    }

    private AttemptView attemptView(long attemptId) {
        return jdbc.queryForObject("SELECT * FROM maintenance_attempt WHERE id=?", (rs, i) -> new AttemptView(
                rs.getLong("id"), rs.getLong("obligation_id"), rs.getInt("attempt_no"), rs.getString("status"),
                toInstant(rs, "window_start"), toInstant(rs, "window_end"),
                jdbc.queryForObject("SELECT COUNT(*) FROM maintenance_checklist_item WHERE attempt_id=?",
                        Integer.class, attemptId),
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM maintenance_checklist_item WHERE attempt_id=? AND done=TRUE",
                        Integer.class, attemptId),
                toInstant(rs, "suspended_at"), rs.getObject("suspend_rescue_id", Long.class),
                toInstant(rs, "completed_at"), rs.getLong("version"), toInstant(rs, "created_at")), attemptId);
    }

    private ObligationView toObligationView(ObligationRow r) {
        return jdbc.queryForObject("SELECT * FROM maintenance_obligation WHERE id=?",
                (rs, i) -> new ObligationView(rs.getLong("id"), rs.getLong("elevator_id"),
                        rs.getObject("base_maintenance_id", Long.class), toInstant(rs, "due_at"),
                        rs.getString("status"), rs.getLong("version"), toInstant(rs, "created_at"),
                        toInstant(rs, "fulfilled_at")), r.id());
    }

    private static Instant toInstant(java.sql.ResultSet rs, String col) throws java.sql.SQLException {
        LocalDateTime v = rs.getObject(col, LocalDateTime.class);
        return v == null ? null : v.toInstant(ZoneOffset.UTC);
    }

    private static Instant toInstant(java.sql.ResultSet rs, int col) throws java.sql.SQLException {
        LocalDateTime v = rs.getObject(col, LocalDateTime.class);
        return v == null ? null : v.toInstant(ZoneOffset.UTC);
    }
}
