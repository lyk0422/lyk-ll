package com.example.starter.service;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import com.example.starter.domain.AttemptStatus;
import com.example.starter.domain.ObligationStatus;
import com.example.starter.error.ApiException;
import com.example.starter.service.ElevatorService.ElevatorRow;
import com.example.starter.web.dto.ApiDtos.AttemptCompleteRequest;
import com.example.starter.web.dto.ApiDtos.AttemptDispatchRequest;
import com.example.starter.web.dto.ApiDtos.AttemptItemsRequest;
import com.example.starter.web.dto.ApiDtos.AttemptStartRequest;
import com.example.starter.web.dto.ApiDtos.AttemptView;
import com.example.starter.web.dto.ApiDtos.MaintenanceView;
import com.example.starter.web.dto.ApiDtos.ObligationView;
import com.example.starter.web.dto.ApiDtos.ScanRequest;
import com.example.starter.web.dto.ApiDtos.ScanView;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 维保履约续派：扫描生成履约义务（到期前 24 小时可派，恰在起点允许，同梯同周期唯一）；
 * 尝试需两名合成持证人员并占半开作业窗，人员同窗不可重叠，任一不足整批不占资源；
 * 救援接报原子挂起未终结尝试并释放人员、保留已做清单；结案后可续派新尝试；
 * 新尝试完成全部清单并双人共同确认才登记一条有效维保记录，
 * 登记记录、终结义务与释放资源同事务；挂起旧尝试回报只记迟到。
 * 全部写操作 requestId 幂等 + expectedVersion 乐观锁（版本从 1 开始，成功变更加 1）。
 */
@Service
public class MaintenancePlanService {

    /** 派发窗口：到期时刻前 24 小时开放，恰在起点允许 */
    static final long DISPATCH_OPEN_HOURS = 24;
    /** 维保清单（合成规则固定集合），新尝试须全部完成 */
    static final List<String> REQUIRED_ITEMS = List.of("BRAKE_CHECK", "DOOR_INTERLOCK", "ALARM_TEST");

    /** 履约义务行 */
    public record ObligationRow(long id, long elevatorId, LocalDate baseDay, LocalDate dueDay,
                                Instant dueAt, Instant dispatchOpenAt, String status, long version) {
    }

    /** 履约尝试行 */
    public record AttemptRow(long id, long obligationId, long elevatorId, long staff1Id, long staff2Id,
                             Instant windowStart, Instant windowEnd, String status, long version) {
    }

    static final RowMapper<ObligationRow> OBLIGATION_MAPPER = (rs, i) -> new ObligationRow(
            rs.getLong("id"), rs.getLong("elevator_id"),
            rs.getObject("base_day", LocalDate.class), rs.getObject("due_day", LocalDate.class),
            toInstant(rs, "due_at"), toInstant(rs, "dispatch_open_at"),
            rs.getString("status"), rs.getLong("version"));

    static final RowMapper<AttemptRow> ATTEMPT_MAPPER = (rs, i) -> new AttemptRow(
            rs.getLong("id"), rs.getLong("obligation_id"), rs.getLong("elevator_id"),
            rs.getLong("staff1_id"), rs.getLong("staff2_id"),
            toInstant(rs, "window_start"), toInstant(rs, "window_end"),
            rs.getString("status"), rs.getLong("version"));

    private final JdbcTemplate jdbc;
    private final ElevatorService elevatorService;
    private final MaintenanceService maintenanceService;
    private final IdempotencyService idempotency;
    private final Clock clock;

    public MaintenancePlanService(JdbcTemplate jdbc, ElevatorService elevatorService,
                                  MaintenanceService maintenanceService,
                                  IdempotencyService idempotency, Clock clock) {
        this.jdbc = jdbc;
        this.elevatorService = elevatorService;
        this.maintenanceService = maintenanceService;
        this.idempotency = idempotency;
        this.clock = clock;
    }

    // ---------------------------------------------------------------- 扫描

    public ResponseEntity<String> scan(ScanRequest req) {
        String fp = Fingerprints.of("scope=MAINT_SCAN", "requestId=" + req.requestId(),
                "scannedAt=" + req.scannedAt());
        return idempotency.handle("MAINT_SCAN", req.requestId(), fp, () -> doScan(req));
    }

    private ScanView doScan(ScanRequest req) {
        Instant now = req.scannedAt() != null ? req.scannedAt() : Instant.now(clock);
        // 按 ID 顺序锁定全部电梯，并发扫描串行化且无死锁
        List<ElevatorRow> elevators = jdbc.query("SELECT * FROM elevator ORDER BY id FOR UPDATE",
                ElevatorService.ELEVATOR_MAPPER);
        List<ObligationView> created = new ArrayList<>();
        for (ElevatorRow ev : elevators) {
            LocalDate base = ev.lastMaintenanceDay() != null ? ev.lastMaintenanceDay() : ev.commissionedDay();
            LocalDate dueDay = base.plusDays(MaintenanceService.CYCLE_DAYS);
            Instant dueAt = dueDay.atStartOfDay().toInstant(ZoneOffset.UTC);
            Instant openAt = dueAt.minus(DISPATCH_OPEN_HOURS, ChronoUnit.HOURS);
            if (now.isBefore(openAt)) {
                continue; // 恰在起点允许：now >= openAt 才可派发
            }
            Integer existing = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM maintenance_obligation WHERE elevator_id=? AND due_day=?",
                    Integer.class, ev.id(), dueDay);
            if (existing != null && existing > 0) {
                continue; // 同一电梯同一到期周期只派一项，重复扫描不多派
            }
            jdbc.update("INSERT INTO maintenance_obligation(elevator_id, base_day, due_day, due_at, "
                            + "dispatch_open_at, status, version, created_at) VALUES (?,?,?,?,?,'OPEN',1,?)",
                    ev.id(), base, dueDay, ElevatorService.ts(dueAt), ElevatorService.ts(openAt),
                    ElevatorService.ts(now));
            long id = jdbc.queryForObject(
                    "SELECT id FROM maintenance_obligation WHERE elevator_id=? AND due_day=?",
                    Long.class, ev.id(), dueDay);
            created.add(obligationView(id));
        }
        return new ScanView(created.size(), created);
    }

    // ---------------------------------------------------------------- 派发尝试

    public ResponseEntity<String> dispatchAttempt(long obligationId, AttemptDispatchRequest req) {
        List<Long> sorted = req.staffIds().stream().distinct().sorted().toList();
        String fp = Fingerprints.of("scope=MAINT_DISPATCH", "requestId=" + req.requestId(),
                "obligationId=" + obligationId, "expectedVersion=" + req.expectedVersion(),
                "staffIds=" + sorted.stream().map(String::valueOf).collect(Collectors.joining(",")),
                "windowStart=" + req.windowStart(), "windowEnd=" + req.windowEnd());
        return idempotency.handle("MAINT_DISPATCH", req.requestId(), fp,
                () -> doDispatchAttempt(obligationId, req, sorted));
    }

    private AttemptView doDispatchAttempt(long obligationId, AttemptDispatchRequest req, List<Long> staffIds) {
        ObligationRow ob = findObligation(obligationId);
        elevatorService.lockElevator(ob.elevatorId());
        ob = lockObligation(obligationId);
        if (idempotency.exists("MAINT_DISPATCH", req.requestId())) {
            // 并发同键重放：交由幂等记录仲裁返回首次冻结结果
            return attemptViewByObligation(obligationId);
        }
        checkVersion(req.expectedVersion(), ob.version());
        if (!ObligationStatus.OPEN.name().equals(ob.status())) {
            throw ApiException.unprocessable("OBLIGATION_CLOSED", "义务已结清，不得再派发尝试: " + ob.status());
        }
        Instant now = Instant.now(clock);
        if (now.isBefore(ob.dispatchOpenAt())) {
            throw ApiException.unprocessable("NOT_YET_DISPATCHABLE", "未到可派发起点（到期前24小时）");
        }
        if (staffIds.size() != 2) {
            throw ApiException.unprocessable("TWO_STAFF_REQUIRED", "维保任务须恰两名持证人员");
        }
        if (!req.windowStart().isBefore(req.windowEnd())) {
            throw ApiException.badRequest("INVALID_WINDOW", "作业窗须满足 windowStart < windowEnd（半开区间）");
        }
        for (Long staffId : staffIds) {
            List<Boolean> certified = jdbc.query("SELECT certified FROM staff WHERE id=?",
                    (rs, i) -> rs.getBoolean(1), staffId);
            if (certified.isEmpty()) {
                throw ApiException.notFound("人员不存在: " + staffId);
            }
            if (!certified.get(0)) {
                throw ApiException.unprocessable("UNQUALIFIED_STAFF", "人员未持有效证书: " + staffId);
            }
        }
        if (elevatorService.hasActiveRescue(ob.elevatorId())) {
            throw ApiException.unprocessable("RESCUE_IN_FLIGHT", "存在进行中救援，维保任务已被挂起，待结案后续派");
        }
        Integer activeAttempts = jdbc.queryForObject(
                "SELECT COUNT(*) FROM maintenance_attempt WHERE obligation_id=? "
                        + "AND status IN ('SCHEDULED','IN_PROGRESS')",
                Integer.class, obligationId);
        if (activeAttempts != null && activeAttempts > 0) {
            throw ApiException.unprocessable("ACTIVE_ATTEMPT_EXISTS", "义务存在未终结尝试，不得重复派发");
        }
        // 人员同窗不可重叠：半开区间 [s,e) 重叠 iff s < 新end 且 新start < e；任一不足整批不占资源
        Integer overlap = jdbc.queryForObject(
                "SELECT COUNT(*) FROM maintenance_attempt WHERE status IN ('SCHEDULED','IN_PROGRESS') "
                        + "AND (staff1_id IN (?,?) OR staff2_id IN (?,?)) "
                        + "AND window_start < ? AND ? < window_end",
                Integer.class, staffIds.get(0), staffIds.get(1), staffIds.get(0), staffIds.get(1),
                ElevatorService.ts(req.windowEnd()), ElevatorService.ts(req.windowStart()));
        if (overlap != null && overlap > 0) {
            throw ApiException.unprocessable("STAFF_WINDOW_OVERLAP", "人员在半开作业窗内存在重叠，整批不占资源");
        }
        jdbc.update("INSERT INTO maintenance_attempt(obligation_id, elevator_id, staff1_id, staff2_id, "
                        + "window_start, window_end, status, version, created_at) "
                        + "VALUES (?,?,?,?,?,?,'SCHEDULED',1,?)",
                obligationId, ob.elevatorId(), staffIds.get(0), staffIds.get(1),
                ElevatorService.ts(req.windowStart()), ElevatorService.ts(req.windowEnd()),
                ElevatorService.ts(now));
        jdbc.update("UPDATE maintenance_obligation SET version=version+1 WHERE id=?", obligationId);
        long attemptId = jdbc.queryForObject(
                "SELECT MAX(id) FROM maintenance_attempt WHERE obligation_id=?", Long.class, obligationId);
        return attemptView(attemptId);
    }

    // ---------------------------------------------------------------- 开始作业

    public ResponseEntity<String> startAttempt(long attemptId, AttemptStartRequest req) {
        String fp = Fingerprints.of("scope=MAINT_START", "requestId=" + req.requestId(),
                "attemptId=" + attemptId, "expectedVersion=" + req.expectedVersion(),
                "startedAt=" + req.startedAt());
        return idempotency.handle("MAINT_START", req.requestId(), fp, () -> doStartAttempt(attemptId, req));
    }

    private AttemptView doStartAttempt(long attemptId, AttemptStartRequest req) {
        AttemptRow at = findAttempt(attemptId);
        elevatorService.lockElevator(at.elevatorId());
        at = lockAttempt(attemptId);
        if (idempotency.exists("MAINT_START", req.requestId())) {
            return attemptView(attemptId);
        }
        checkVersion(req.expectedVersion(), at.version());
        if (!AttemptStatus.SCHEDULED.name().equals(at.status())) {
            throw ApiException.unprocessable("ILLEGAL_STATE", "当前状态不允许开始作业: " + at.status());
        }
        // 开始时重查：两人证书仍有效且该梯无进行中救援
        requireCertified(at.staff1Id());
        requireCertified(at.staff2Id());
        if (elevatorService.hasActiveRescue(at.elevatorId())) {
            throw ApiException.unprocessable("RESCUE_IN_FLIGHT", "存在进行中救援，不得开始维保作业");
        }
        Instant at0 = req.startedAt() != null ? req.startedAt() : Instant.now(clock);
        jdbc.update("UPDATE maintenance_attempt SET status='IN_PROGRESS', started_at=?, version=version+1 "
                + "WHERE id=?", ElevatorService.ts(at0), attemptId);
        return attemptView(attemptId);
    }

    // ---------------------------------------------------------------- 清单回报

    public ResponseEntity<String> reportItems(long attemptId, AttemptItemsRequest req) {
        List<String> items = req.items().stream().distinct().sorted().toList();
        String fp = Fingerprints.of("scope=MAINT_ITEMS", "requestId=" + req.requestId(),
                "attemptId=" + attemptId, "expectedVersion=" + req.expectedVersion(),
                "items=" + String.join(",", items), "reportedAt=" + req.reportedAt());
        return idempotency.handle("MAINT_ITEMS", req.requestId(), fp,
                () -> doReportItems(attemptId, req, items));
    }

    private AttemptView doReportItems(long attemptId, AttemptItemsRequest req, List<String> items) {
        AttemptRow at = findAttempt(attemptId);
        elevatorService.lockElevator(at.elevatorId());
        at = lockAttempt(attemptId);
        if (idempotency.exists("MAINT_ITEMS", req.requestId())) {
            return attemptView(attemptId);
        }
        checkVersion(req.expectedVersion(), at.version());
        if (!AttemptStatus.IN_PROGRESS.name().equals(at.status())) {
            throw ApiException.unprocessable("ILLEGAL_STATE", "当前状态不允许清单回报: " + at.status());
        }
        for (String item : items) {
            if (!REQUIRED_ITEMS.contains(item)) {
                throw ApiException.badRequest("UNKNOWN_ITEM", "未知清单项: " + item);
            }
        }
        Instant at0 = req.reportedAt() != null ? req.reportedAt() : Instant.now(clock);
        for (String item : items) {
            Integer n = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM maintenance_attempt_item WHERE attempt_id=? AND item=?",
                    Integer.class, attemptId, item);
            if (n == null || n == 0) {
                jdbc.update("INSERT INTO maintenance_attempt_item(attempt_id, item, created_at) VALUES (?,?,?)",
                        attemptId, item, ElevatorService.ts(at0));
            }
        }
        jdbc.update("UPDATE maintenance_attempt SET version=version+1 WHERE id=?", attemptId);
        return attemptView(attemptId);
    }

    // ---------------------------------------------------------------- 完成确认

    public ResponseEntity<String> completeAttempt(long attemptId, AttemptCompleteRequest req) {
        List<Long> confirm = req.confirmStaffIds().stream().distinct().sorted().toList();
        String fp = Fingerprints.of("scope=MAINT_COMPLETE", "requestId=" + req.requestId(),
                "attemptId=" + attemptId, "expectedVersion=" + req.expectedVersion(),
                "confirmStaffIds=" + confirm.stream().map(String::valueOf).collect(Collectors.joining(",")),
                "completedAt=" + req.completedAt());
        return idempotency.handle("MAINT_COMPLETE", req.requestId(), fp,
                () -> doCompleteAttempt(attemptId, req, confirm));
    }

    private AttemptView doCompleteAttempt(long attemptId, AttemptCompleteRequest req, List<Long> confirm) {
        AttemptRow at = findAttempt(attemptId);
        // 与救援接报竞争的一致性：双方均先锁电梯行，串行后形成唯一终态
        ElevatorRow ev = elevatorService.lockElevator(at.elevatorId());
        at = lockAttempt(attemptId);
        if (idempotency.exists("MAINT_COMPLETE", req.requestId())) {
            return attemptView(attemptId);
        }
        checkVersion(req.expectedVersion(), at.version());
        List<Long> assigned = List.of(Math.min(at.staff1Id(), at.staff2Id()), Math.max(at.staff1Id(), at.staff2Id()));
        if (!confirm.equals(assigned)) {
            throw ApiException.unprocessable("DUAL_CONFIRM_REQUIRED", "须两名作业人员共同确认");
        }
        Instant completedAt = req.completedAt() != null ? req.completedAt() : Instant.now(clock);
        LocalDate performedDay = completedAt.atZone(ZoneOffset.UTC).toLocalDate();

        if (AttemptStatus.SUSPENDED.name().equals(at.status())) {
            // 旧尝试回报只记迟到：登记迟到事件，不当本周期维保完成，义务保持 OPEN
            MaintenanceView late = maintenanceService.insertLateEvent(at.elevatorId(), req.requestId(),
                    performedDay, "救援挂起后的旧尝试回报，仅记迟到事件");
            jdbc.update("UPDATE maintenance_attempt SET status='LATE_REPORTED', completed_at=?, "
                            + "maintenance_record_id=?, version=version+1 WHERE id=?",
                    ElevatorService.ts(completedAt), late.id(), attemptId);
            return attemptView(attemptId);
        }
        if (!AttemptStatus.IN_PROGRESS.name().equals(at.status())) {
            throw ApiException.unprocessable("ILLEGAL_STATE", "当前状态不允许完成确认: " + at.status());
        }
        List<String> done = doneItems(attemptId);
        if (!done.containsAll(REQUIRED_ITEMS)) {
            throw ApiException.unprocessable("CHECKLIST_INCOMPLETE",
                    "须完成全部清单项: " + REQUIRED_ITEMS + "，已完成: " + done);
        }
        // 登记有效维保记录、终结义务、释放资源（状态推进即释放窗口）同事务
        MaintenanceView record = maintenanceService.registerEffective(ev, req.requestId(), performedDay,
                "履约尝试完成，双人共同确认");
        jdbc.update("UPDATE maintenance_attempt SET status='COMPLETED', completed_at=?, "
                        + "maintenance_record_id=?, version=version+1 WHERE id=?",
                ElevatorService.ts(completedAt), record.id(), attemptId);
        jdbc.update("UPDATE maintenance_obligation SET status='FULFILLED', settled_by='ATTEMPT', "
                + "settled_at=?, version=version+1 WHERE id=?",
                ElevatorService.ts(completedAt), at.obligationId());
        return attemptView(attemptId);
    }

    // ---------------------------------------------------------------- 救援挂起

    /**
     * 救援接报时原子挂起该梯未终结维保尝试并释放人员（状态推进即释放窗口），
     * 已做清单保留，到期义务不变。须在接报事务内调用。
     */
    public void suspendUnfinished(long elevatorId, Instant at) {
        jdbc.update("UPDATE maintenance_attempt SET status='SUSPENDED', suspended_at=?, version=version+1 "
                        + "WHERE elevator_id=? AND status IN ('SCHEDULED','IN_PROGRESS')",
                ElevatorService.ts(at), elevatorId);
    }

    // ---------------------------------------------------------------- 查询

    @Transactional(readOnly = true)
    public List<ObligationView> listObligations(Long elevatorId) {
        if (elevatorId == null) {
            return jdbc.query("SELECT * FROM maintenance_obligation ORDER BY id", OBLIGATION_MAPPER)
                    .stream().map(this::toObligationView).toList();
        }
        return jdbc.query("SELECT * FROM maintenance_obligation WHERE elevator_id=? ORDER BY id",
                OBLIGATION_MAPPER, elevatorId).stream().map(this::toObligationView).toList();
    }

    @Transactional(readOnly = true)
    public ObligationView getObligation(long obligationId) {
        return obligationView(obligationId);
    }

    @Transactional(readOnly = true)
    public List<AttemptView> listAttempts(long obligationId) {
        findObligation(obligationId);
        return jdbc.query("SELECT * FROM maintenance_attempt WHERE obligation_id=? ORDER BY id",
                ATTEMPT_MAPPER, obligationId).stream().map(this::toAttemptView).toList();
    }

    @Transactional(readOnly = true)
    public AttemptView getAttempt(long attemptId) {
        return attemptView(attemptId);
    }

    // ---------------------------------------------------------------- 内部

    private void requireCertified(long staffId) {
        List<Boolean> certified = jdbc.query("SELECT certified FROM staff WHERE id=?",
                (rs, i) -> rs.getBoolean(1), staffId);
        if (certified.isEmpty()) {
            throw ApiException.notFound("人员不存在: " + staffId);
        }
        if (!certified.get(0)) {
            throw ApiException.unprocessable("UNQUALIFIED_STAFF", "人员未持有效证书: " + staffId);
        }
    }

    private void checkVersion(long expectedVersion, long actualVersion) {
        if (expectedVersion != actualVersion) {
            throw ApiException.conflict("VERSION_CONFLICT",
                    "版本冲突：expectedVersion=" + expectedVersion + "，当前版本=" + actualVersion);
        }
    }

    private ObligationRow findObligation(long obligationId) {
        List<ObligationRow> rows = jdbc.query("SELECT * FROM maintenance_obligation WHERE id=?",
                OBLIGATION_MAPPER, obligationId);
        if (rows.isEmpty()) {
            throw ApiException.notFound("履约义务不存在: " + obligationId);
        }
        return rows.get(0);
    }

    private ObligationRow lockObligation(long obligationId) {
        List<ObligationRow> rows = jdbc.query("SELECT * FROM maintenance_obligation WHERE id=? FOR UPDATE",
                OBLIGATION_MAPPER, obligationId);
        if (rows.isEmpty()) {
            throw ApiException.notFound("履约义务不存在: " + obligationId);
        }
        return rows.get(0);
    }

    private AttemptRow findAttempt(long attemptId) {
        List<AttemptRow> rows = jdbc.query("SELECT * FROM maintenance_attempt WHERE id=?",
                ATTEMPT_MAPPER, attemptId);
        if (rows.isEmpty()) {
            throw ApiException.notFound("履约尝试不存在: " + attemptId);
        }
        return rows.get(0);
    }

    private AttemptRow lockAttempt(long attemptId) {
        List<AttemptRow> rows = jdbc.query("SELECT * FROM maintenance_attempt WHERE id=? FOR UPDATE",
                ATTEMPT_MAPPER, attemptId);
        if (rows.isEmpty()) {
            throw ApiException.notFound("履约尝试不存在: " + attemptId);
        }
        return rows.get(0);
    }

    private List<String> doneItems(long attemptId) {
        return jdbc.query("SELECT item FROM maintenance_attempt_item WHERE attempt_id=? ORDER BY item",
                (rs, i) -> rs.getString(1), attemptId);
    }

    private ObligationView obligationView(long obligationId) {
        return toObligationView(findObligation(obligationId));
    }

    private AttemptView attemptView(long attemptId) {
        return toAttemptView(findAttempt(attemptId));
    }

    private AttemptView attemptViewByObligation(long obligationId) {
        List<AttemptRow> rows = jdbc.query(
                "SELECT * FROM maintenance_attempt WHERE obligation_id=? ORDER BY id DESC LIMIT 1",
                ATTEMPT_MAPPER, obligationId);
        if (rows.isEmpty()) {
            throw ApiException.notFound("履约尝试不存在，义务: " + obligationId);
        }
        return toAttemptView(rows.get(0));
    }

    private ObligationView toObligationView(ObligationRow r) {
        return jdbc.queryForObject("SELECT * FROM maintenance_obligation WHERE id=?",
                (rs, i) -> new ObligationView(rs.getLong("id"), rs.getLong("elevator_id"),
                        rs.getObject("base_day", LocalDate.class), rs.getObject("due_day", LocalDate.class),
                        toInstant(rs, "due_at"), toInstant(rs, "dispatch_open_at"),
                        rs.getString("status"), rs.getLong("version"),
                        rs.getString("settled_by"), toInstant(rs, "settled_at"), toInstant(rs, "created_at")),
                r.id());
    }

    private AttemptView toAttemptView(AttemptRow r) {
        return jdbc.queryForObject("SELECT * FROM maintenance_attempt WHERE id=?",
                (rs, i) -> new AttemptView(rs.getLong("id"), rs.getLong("obligation_id"),
                        rs.getLong("elevator_id"),
                        List.of(rs.getLong("staff1_id"), rs.getLong("staff2_id")),
                        toInstant(rs, "window_start"), toInstant(rs, "window_end"),
                        rs.getString("status"), rs.getLong("version"), doneItems(r.id()),
                        rs.getObject("maintenance_record_id", Long.class),
                        toInstant(rs, "created_at"), toInstant(rs, "started_at"),
                        toInstant(rs, "suspended_at"), toInstant(rs, "completed_at")),
                r.id());
    }

    private static Instant toInstant(java.sql.ResultSet rs, String col) throws java.sql.SQLException {
        LocalDateTime v = rs.getObject(col, LocalDateTime.class);
        return v == null ? null : v.toInstant(ZoneOffset.UTC);
    }
}
