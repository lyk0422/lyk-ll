package com.example.starter.service;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import com.example.starter.domain.AttemptStatus;
import com.example.starter.domain.ChainAction;
import com.example.starter.domain.MaintenanceResult;
import com.example.starter.domain.ObligationStatus;
import com.example.starter.domain.StopReason;
import com.example.starter.error.ApiException;
import com.example.starter.service.ElevatorService.ElevatorRow;
import com.example.starter.service.IdempotencyService.TryResult;
import com.example.starter.web.dto.ApiDtos.AttemptCompleteRequest;
import com.example.starter.web.dto.ApiDtos.AttemptEventView;
import com.example.starter.web.dto.ApiDtos.AttemptItemRequest;
import com.example.starter.web.dto.ApiDtos.AttemptView;
import com.example.starter.web.dto.ApiDtos.MaintenanceItemInput;
import com.example.starter.web.dto.ApiDtos.MaintenanceItemView;
import com.example.starter.web.dto.ApiDtos.ObligationView;
import com.example.starter.web.dto.ApiDtos.PlanAttemptRequest;
import com.example.starter.web.dto.ApiDtos.PlanScanRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 周期维保计划编排与救援抢占后的履约续派。
 *
 * <p>义务绑定电梯与固定 UTC 到期时刻（基准日 + 15 日 00:00），到期前 24 小时起可派发，
 * 同一电梯同一到期周期仅一项义务；每次派发产生一项尝试，须两名持证人员在半开作业窗内
 * 完成全部清单并双人共同确认，才登记一条有效维保记录并终结义务。
 * 接报救援原子挂起未完成尝试并释放人员、保留清单；结案后续派新尝试，旧尝试回报仅记迟到。
 */
@Service
public class MaintenancePlanService {

    /** 到期前可派发提前量：24 小时，恰在起点允许 */
    static final long DISPATCH_LEAD_HOURS = 24;

    static final String SCOPE_SCAN = "PLAN_SCAN";
    static final String SCOPE_ATTEMPT = "PLAN_ATTEMPT";
    static final String SCOPE_ITEM = "PLAN_ITEM";
    static final String SCOPE_COMPLETE = "PLAN_COMPLETE";

    /** 履约义务行 */
    public record ObligationRow(long id, long elevatorId, LocalDate baseDay, Instant dueAt, String status,
                                String settleSource, Long completedAttemptId, int version,
                                Instant createdAt, Instant updatedAt) {
    }

    /** 履约尝试行 */
    public record AttemptRow(long id, long obligationId, long elevatorId, String requestId, String status,
                             Instant windowStart, Instant windowEnd, Long rescueId,
                             Instant startedAt, Instant suspendedAt, Instant completedAt, int version) {
    }

    static final RowMapper<ObligationRow> OBLIGATION_MAPPER = (rs, i) -> new ObligationRow(
            rs.getLong("id"), rs.getLong("elevator_id"), rs.getObject("base_day", LocalDate.class),
            rs.getObject("due_at", LocalDateTime.class).toInstant(ZoneOffset.UTC),
            rs.getString("status"), rs.getString("settle_source"),
            rs.getObject("completed_attempt_id", Long.class), rs.getInt("version"),
            rs.getObject("created_at", LocalDateTime.class).toInstant(ZoneOffset.UTC),
            rs.getObject("updated_at", LocalDateTime.class).toInstant(ZoneOffset.UTC));

    static final RowMapper<AttemptRow> ATTEMPT_MAPPER = (rs, i) -> new AttemptRow(
            rs.getLong("id"), rs.getLong("obligation_id"), rs.getLong("elevator_id"),
            rs.getString("request_id"), rs.getString("status"),
            rs.getObject("window_start", LocalDateTime.class).toInstant(ZoneOffset.UTC),
            rs.getObject("window_end", LocalDateTime.class).toInstant(ZoneOffset.UTC),
            rs.getObject("rescue_id", Long.class),
            rs.getObject("started_at", LocalDateTime.class).toInstant(ZoneOffset.UTC),
            toInstant(rs, "suspended_at"), toInstant(rs, "completed_at"), rs.getInt("version"));

    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final IdempotencyService idempotency;
    private final ElevatorService elevatorService;

    public MaintenancePlanService(JdbcTemplate jdbc, Clock clock, IdempotencyService idempotency,
                                  ElevatorService elevatorService) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.idempotency = idempotency;
        this.elevatorService = elevatorService;
    }

    // ---------------------------------------------------------------- 扫描编排

    /** 扫描并生成到期维保履约义务（重复扫描不多派）；requestId 缺省按时刻生成。 */
    public ResponseEntity<String> scan(PlanScanRequest req) {
        Instant at = req.at() != null ? req.at() : Instant.now(clock);
        String requestId = req.requestId() != null && !req.requestId().isBlank()
                ? req.requestId() : "SCAN@" + at;
        String fp = Fingerprints.structured("scope=" + SCOPE_SCAN, "requestId=" + requestId, "at=" + at);
        return idempotency.handleResult(SCOPE_SCAN, requestId, fp, () -> TryResult.ok(doScan(at)));
    }

    private List<ObligationView> doScan(Instant at) {
        // 锁定全部电梯行串行化扫描，避免重复扫描并发插入同周期义务
        List<ElevatorRow> elevators = jdbc.query(
                "SELECT * FROM elevator ORDER BY id FOR UPDATE", ElevatorService.ELEVATOR_MAPPER);
        List<ObligationView> created = new ArrayList<>();
        for (ElevatorRow ev : elevators) {
            LocalDate base = ev.lastMaintenanceDay() != null ? ev.lastMaintenanceDay() : ev.commissionedDay();
            Instant dueAt = dueAtFor(base);
            // 到期前 24 小时（含恰在起点）才纳入编排；逾期义务仍保持唯一，不重复生成
            if (at.isBefore(dueAt.minusSeconds(DISPATCH_LEAD_HOURS * 3600))) {
                continue;
            }
            Integer exists = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM maintenance_obligation WHERE elevator_id=? AND due_at=?",
                    Integer.class, ev.id(), ElevatorService.ts(dueAt));
            if (exists != null && exists > 0) {
                continue;
            }
            Instant now = Instant.now(clock);
            jdbc.update("INSERT INTO maintenance_obligation(elevator_id, base_day, due_at, status, version, "
                            + "created_at, updated_at) VALUES (?,?,?,'OPEN',1,?,?)",
                    ev.id(), base, ElevatorService.ts(dueAt), ElevatorService.ts(now), ElevatorService.ts(now));
            long id = jdbc.queryForObject(
                    "SELECT id FROM maintenance_obligation WHERE elevator_id=? AND due_at=?",
                    Long.class, ev.id(), ElevatorService.ts(dueAt));
            created.add(toObligationView(lockObligation(id)));
        }
        return created;
    }

    // ---------------------------------------------------------------- 派发尝试

    /** 派发一次履约尝试：双人持证、半开作业窗、同窗人员不重叠，任一不足整批不占资源。 */
    public ResponseEntity<String> dispatch(PlanAttemptRequest req) {
        Instant startedAt = req.startedAt() != null ? req.startedAt() : Instant.now(clock);
        List<Long> staffIds = req.staffIds().stream().distinct().sorted().toList();
        List<MaintenanceItemInput> checklist = req.checklist();
        String fp = Fingerprints.structured("scope=" + SCOPE_ATTEMPT, "requestId=" + req.requestId(),
                "elevatorId=" + req.elevatorId(), "obligationId=" + req.obligationId(), "staffIds=" + Fingerprints.list(
                        staffIds.stream().map(String::valueOf).toList()),
                "windowStart=" + req.windowStart(), "windowEnd=" + req.windowEnd(), "startedAt=" + startedAt,
                "checklist=" + fingerprintChecklist(checklist));
        return idempotency.handleResult(SCOPE_ATTEMPT, req.requestId(), fp,
                () -> TryResult.ok(doDispatch(req, staffIds, checklist, startedAt)));
    }

    private AttemptView doDispatch(PlanAttemptRequest req, List<Long> staffIds,
                                   List<MaintenanceItemInput> checklist, Instant startedAt) {
        // 参数非法 400：双人去重后仍不足两人、清单编码重复、作业窗非半开
        if (staffIds.size() != 2) {
            throw ApiException.badRequest("INVALID_STAFF_SET", "履约任务须恰两名不同的持证人员");
        }
        long distinctItemCodes = checklist.stream().map(MaintenanceItemInput::code).distinct().count();
        if (distinctItemCodes != checklist.size()) {
            throw ApiException.badRequest("INVALID_CHECKLIST", "清单编码不得重复");
        }
        if (!req.windowEnd().isAfter(req.windowStart())) {
            throw ApiException.badRequest("INVALID_WINDOW", "作业窗终点须严格晚于起点（半开区间）");
        }
        if (startedAt.isBefore(req.windowStart()) || !startedAt.isBefore(req.windowEnd())) {
            throw ApiException.badRequest("START_OUTSIDE_WINDOW", "开始时刻须落在半开作业窗 [起点, 终点) 内");
        }

        // 统一加锁顺序：电梯 → 义务 → 尝试
        ElevatorRow ev = elevatorService.lockElevator(req.elevatorId());
        ObligationRow ob;
        if (req.obligationId() != null) {
            ObligationRow specified = lockObligation(req.obligationId());
            if (specified.elevatorId() != ev.id()) {
                throw ApiException.notFound("该义务不属于指定电梯: " + req.obligationId());
            }
            if (ObligationStatus.FULFILLED.name().equals(specified.status())) {
                throw ApiException.unprocessable("OBLIGATION_FULFILLED", "该到期周期义务已终结，不得再派发尝试");
            }
            ob = specified;
        } else {
            ob = lockOpenObligation(ev.id());
            if (ob == null) {
                throw ApiException.notFound("该电梯暂无到期可派发的未完成履约义务");
            }
        }
        Instant dispatchableAt = ob.dueAt().minusSeconds(DISPATCH_LEAD_HOURS * 3600);
        if (startedAt.isBefore(dispatchableAt)) {
            throw ApiException.unprocessable("NOT_DISPATCHABLE",
                    "距到期超过 24 小时，暂不可派发；最早可派发时刻 " + dispatchableAt);
        }
        Integer active = jdbc.queryForObject(
                "SELECT COUNT(*) FROM maintenance_attempt WHERE obligation_id=? AND status='PLANNED'",
                Integer.class, ob.id());
        if (active != null && active > 0) {
            throw ApiException.unprocessable("ATTEMPT_IN_FLIGHT", "同一到期周期已有进行中的尝试，须先结案或被救援挂起");
        }
        // 开始时重查：无进行中救援
        if (elevatorService.hasActiveRescue(ev.id())) {
            throw ApiException.unprocessable("RESCUE_IN_FLIGHT", "存在进行中的救援单，不得开始维保尝试");
        }
        // 开始时重查：两名人员均存在且证书有效
        for (Long staffId : staffIds) {
            List<Boolean> certified = jdbc.query("SELECT certified FROM staff WHERE id=?",
                    (rs, i) -> rs.getBoolean(1), staffId);
            if (certified.isEmpty()) {
                throw ApiException.notFound("人员不存在: " + staffId);
            }
            if (!certified.get(0)) {
                throw ApiException.unprocessable("UNQUALIFIED_STAFF", "人员证书已失效: " + staffId);
            }
        }
        // 同窗人员不可重叠（半开区间）：任一人员在重叠窗内已有进行中尝试则整批不占资源
        Integer overlap = jdbc.queryForObject(
                "SELECT COUNT(*) FROM maintenance_attempt a "
                        + "JOIN maintenance_attempt_staff s ON s.attempt_id = a.id AND s.role='ASSIGN' "
                        + "WHERE a.status='PLANNED' AND s.staff_id IN (?,?) "
                        + "AND a.window_start < ? AND a.window_end > ?",
                Integer.class, staffIds.get(0), staffIds.get(1),
                ElevatorService.ts(req.windowEnd()), ElevatorService.ts(req.windowStart()));
        if (overlap != null && overlap > 0) {
            throw ApiException.unprocessable("STAFF_WINDOW_CONFLICT", "人员在重叠作业窗内已有维保任务，整批不派发");
        }

        Instant now = Instant.now(clock);
        jdbc.update("INSERT INTO maintenance_attempt(obligation_id, elevator_id, request_id, status, "
                        + "window_start, window_end, started_at, version, created_at) "
                        + "VALUES (?,?,?,'PLANNED',?,?,?,1,?)",
                ob.id(), ev.id(), req.requestId(), ElevatorService.ts(req.windowStart()),
                ElevatorService.ts(req.windowEnd()), ElevatorService.ts(startedAt), ElevatorService.ts(now));
        long attemptId = jdbc.queryForObject(
                "SELECT id FROM maintenance_attempt WHERE request_id=?", Long.class, req.requestId());
        for (Long staffId : staffIds) {
            jdbc.update("INSERT INTO maintenance_attempt_staff(attempt_id, staff_id, role, created_at) "
                    + "VALUES (?,?,'ASSIGN',?)", attemptId, staffId, ElevatorService.ts(now));
        }
        for (MaintenanceItemInput item : checklist) {
            jdbc.update("INSERT INTO maintenance_attempt_item(attempt_id, item_code, item_name, done) "
                    + "VALUES (?,?,?,FALSE)", attemptId, item.code(), item.name());
        }
        appendEvent(attemptId, "CREATED", "派发尝试: 人员" + staffIds + " 窗[" + req.windowStart() + ","
                + req.windowEnd() + ") 清单" + checklist.size() + "项", startedAt);
        return toAttemptView(lockAttempt(attemptId));
    }

    // ---------------------------------------------------------------- 清单回报

    /** 清单完成回报（携带 expectedVersion）；挂起/终结尝试的回报只记迟到，不改清单与版本。 */
    public ResponseEntity<String> reportItem(long attemptId, AttemptItemRequest req) {
        Instant at = req.at() != null ? req.at() : Instant.now(clock);
        String fp = Fingerprints.structured("scope=" + SCOPE_ITEM, "requestId=" + req.requestId(),
                "attemptId=" + attemptId, "itemCode=" + req.itemCode(), "done=" + req.done(),
                "expectedVersion=" + req.expectedVersion(), "at=" + at);
        return idempotency.handleResult(SCOPE_ITEM, req.requestId(), fp,
                () -> TryResult.ok(doReportItem(attemptId, req, at)));
    }

    private AttemptView doReportItem(long attemptId, AttemptItemRequest req, Instant at) {
        AttemptRow a = lockAttempt(attemptId);
        if (AttemptStatus.SUSPENDED.name().equals(a.status())
                || AttemptStatus.COMPLETED.name().equals(a.status())
                || AttemptStatus.ABORTED.name().equals(a.status())) {
            // 旧尝试回报只记迟到，不动清单、不变版本、不能作为本周期完成
            appendEvent(attemptId, "LATE_REPORT", "清单回报迟到（尝试状态 " + a.status() + "）: " + req.itemCode(), at);
            return toAttemptView(lockAttempt(attemptId));
        }
        if (!req.done()) {
            throw ApiException.badRequest("INVALID_ITEM_REPORT", "清单回报仅接受完成（done=true），不可撤销");
        }
        if (a.version() != req.expectedVersion()) {
            throw ApiException.conflict("VERSION_CONFLICT",
                    "版本不一致：expected=" + req.expectedVersion() + " actual=" + a.version());
        }
        Integer exists = jdbc.queryForObject(
                "SELECT COUNT(*) FROM maintenance_attempt_item WHERE attempt_id=? AND item_code=?",
                Integer.class, attemptId, req.itemCode());
        if (exists == null || exists == 0) {
            throw ApiException.notFound("清单项不存在: " + req.itemCode());
        }
        int updated = jdbc.update("UPDATE maintenance_attempt_item SET done=TRUE, done_at=? "
                + "WHERE attempt_id=? AND item_code=? AND done=FALSE",
                ElevatorService.ts(at), attemptId, req.itemCode());
        if (updated > 0) {
            jdbc.update("UPDATE maintenance_attempt SET version=version+1 WHERE id=?", attemptId);
        }
        appendEvent(attemptId, "ITEM_DONE",
                (updated > 0 ? "清单完成: " : "清单重复回报: ") + req.itemCode(), at);
        return toAttemptView(lockAttempt(attemptId));
    }

    // ---------------------------------------------------------------- 双人确认完成

    /** 双人共同确认完成：全部清单完成且确认双人即派发双人，登记有效维保记录并终结义务，同事务。 */
    public ResponseEntity<String> complete(long attemptId, AttemptCompleteRequest req) {
        Instant completedAt = req.completedAt() != null ? req.completedAt() : Instant.now(clock);
        List<Long> staffIds = req.staffIds().stream().distinct().sorted().toList();
        String fp = Fingerprints.structured("scope=" + SCOPE_COMPLETE, "requestId=" + req.requestId(),
                "attemptId=" + attemptId,
                "staffIds=" + Fingerprints.list(staffIds.stream().map(String::valueOf).toList()),
                "expectedVersion=" + req.expectedVersion(), "completedAt=" + completedAt);
        return idempotency.handleResult(SCOPE_COMPLETE, req.requestId(), fp,
                () -> doComplete(attemptId, req, staffIds, completedAt));
    }

    private TryResult doComplete(long attemptId, AttemptCompleteRequest req,
                                 List<Long> confirmers, Instant completedAt) {
        if (confirmers.size() != 2) {
            throw ApiException.badRequest("INVALID_STAFF_SET", "完成确认须恰两名不同的持证人员");
        }
        AttemptRow first = getAttempt(attemptId);
        if (first == null) {
            throw ApiException.notFound("履约尝试不存在: " + attemptId);
        }
        // 统一加锁顺序：电梯 → 义务 → 尝试，与接报挂起/手工结清竞争并串行化
        ElevatorRow ev = elevatorService.lockElevator(first.elevatorId());
        ObligationRow ob = lockObligation(first.obligationId());
        AttemptRow a = lockAttempt(attemptId);

        if (AttemptStatus.SUSPENDED.name().equals(a.status())
                || AttemptStatus.ABORTED.name().equals(a.status())) {
            // 旧尝试的完成回报只记迟到，不能登记为本周期维保完成；不改变义务与清单
            appendEvent(attemptId, "LATE_REPORT", "双人完成确认迟到（尝试状态 " + a.status()
                    + "），须续派新尝试", completedAt);
            return TryResult.ok(toAttemptView(lockAttempt(attemptId)));
        }
        if (AttemptStatus.COMPLETED.name().equals(a.status())) {
            throw ApiException.conflict("ATTEMPT_COMPLETED", "尝试已完成，不得重复确认");
        }
        if (a.version() != req.expectedVersion()) {
            throw ApiException.conflict("VERSION_CONFLICT",
                    "版本不一致：expected=" + req.expectedVersion() + " actual=" + a.version());
        }
        if (completedAt.isBefore(a.windowStart()) || !completedAt.isBefore(a.windowEnd())) {
            throw ApiException.unprocessable("COMPLETE_OUTSIDE_WINDOW",
                    "完成确认时刻须落在半开作业窗 [" + a.windowStart() + "," + a.windowEnd() + ") 内");
        }
        for (Long staffId : confirmers) {
            List<Boolean> certified = jdbc.query("SELECT certified FROM staff WHERE id=?",
                    (rs, i) -> rs.getBoolean(1), staffId);
            if (certified.isEmpty()) {
                throw ApiException.notFound("人员不存在: " + staffId);
            }
            if (!certified.get(0)) {
                throw ApiException.unprocessable("UNQUALIFIED_STAFF", "确认人员证书已失效: " + staffId);
            }
        }
        List<Long> assigned = jdbc.query(
                "SELECT staff_id FROM maintenance_attempt_staff WHERE attempt_id=? AND role='ASSIGN' ORDER BY staff_id",
                (rs, i) -> rs.getLong(1), attemptId);
        if (!assigned.equals(confirmers)) {
            throw ApiException.unprocessable("CONFIRMER_MISMATCH",
                    "完成确认双人须与派发双人一致: " + assigned);
        }
        List<String> pending = jdbc.query(
                "SELECT item_code FROM maintenance_attempt_item WHERE attempt_id=? AND done=FALSE ORDER BY item_code",
                (rs, i) -> rs.getString(1), attemptId);
        if (!pending.isEmpty()) {
            throw ApiException.unprocessable("CHECKLIST_INCOMPLETE", "清单尚未全部完成: " + pending);
        }
        if (elevatorService.hasActiveRescue(ev.id())) {
            // 与接报竞争：若救援已接报则本尝试必已被挂起；防御性拒绝
            throw ApiException.unprocessable("RESCUE_IN_FLIGHT", "存在进行中的救援单，不得确认完成");
        }

        LocalDate performedDay = completedAt.atZone(ZoneOffset.UTC).toLocalDate();
        LocalDate dueDay = ob.dueAt().atZone(ZoneOffset.UTC).toLocalDate();
        boolean overdue = performedDay.isAfter(dueDay);
        MaintenanceResult result = overdue ? MaintenanceResult.OVERDUE : MaintenanceResult.COMPLIANT;
        Instant now = Instant.now(clock);

        // 登记记录、终结义务、终结尝试、释放资源全部同一事务
        jdbc.update("INSERT INTO maintenance_record(elevator_id, request_id, attempt_id, performed_day, due_day, "
                        + "result, note, created_at) VALUES (?,?,?,?,?,?,?,?)",
                ev.id(), req.requestId(), attemptId, performedDay, dueDay, result.name(),
                "周期维保尝试双人确认完成", ElevatorService.ts(now));
        if (overdue) {
            jdbc.update("UPDATE elevator SET key_supervision=TRUE, last_maintenance_day=? WHERE id=?",
                    performedDay, ev.id());
            boolean alreadyLocked = elevatorService.openStops(ev.id()).stream()
                    .anyMatch(s -> StopReason.MAINTENANCE_OVERDUE.name().equals(s.reason()));
            if (!alreadyLocked) {
                if ("RUNNING".equals(ev.status())) {
                    elevatorService.stopIfRunning(ev, StopReason.MAINTENANCE_OVERDUE, null, completedAt);
                } else {
                    elevatorService.appendChain(ev.id(), ChainAction.STOP, StopReason.MAINTENANCE_OVERDUE, null, completedAt);
                }
            }
        } else {
            jdbc.update("UPDATE elevator SET last_maintenance_day=? WHERE id=?", performedDay, ev.id());
        }
        jdbc.update("UPDATE maintenance_attempt SET status='COMPLETED', completed_at=?, version=version+1 WHERE id=?",
                ElevatorService.ts(completedAt), attemptId);
        for (Long staffId : confirmers) {
            jdbc.update("INSERT INTO maintenance_attempt_staff(attempt_id, staff_id, role, created_at) "
                    + "VALUES (?,?,'CONFIRM',?)", attemptId, staffId, ElevatorService.ts(now));
        }
        jdbc.update("UPDATE maintenance_obligation SET status='FULFILLED', settle_source='ATTEMPT', "
                        + "completed_attempt_id=?, version=version+1, updated_at=? WHERE id=?",
                attemptId, ElevatorService.ts(now), ob.id());
        appendEvent(attemptId, "COMPLETED", "双人共同确认完成，登记有效维保记录: " + result, completedAt);
        return TryResult.ok(toAttemptView(lockAttempt(attemptId)));
    }

    // ---------------------------------------------------------------- 救援挂起 / 手工结清

    /**
     * 接报救援时原子挂起该电梯全部进行中尝试并释放人员（保留已做清单，不改变到期义务）。
     * 在救援接报事务内、已持有电梯行锁时调用。
     */
    @Transactional
    public void suspendForRescue(long elevatorId, long rescueId, Instant at) {
        List<Long> ids = jdbc.query(
                "SELECT id FROM maintenance_attempt WHERE elevator_id=? AND status='PLANNED' FOR UPDATE",
                (rs, i) -> rs.getLong(1), elevatorId);
        for (Long attemptId : ids) {
            jdbc.update("UPDATE maintenance_attempt SET status='SUSPENDED', suspended_at=?, rescue_id=?, "
                            + "version=version+1 WHERE id=? AND status='PLANNED'",
                    ElevatorService.ts(at), rescueId, attemptId);
            // 释放人员：ASSIGN 关联保留为证据，仅进行中尝试才参与同窗占用
            appendEvent(attemptId, "SUSPENDED", "接报救援 " + rescueId + "，挂起未完成维保并释放人员", at);
        }
    }

    /**
     * 旧手工维保登记成功（合规/逾期）后，将该电梯对应的未完成义务结清一次；
     * 残留进行中/挂起尝试终止（人员不再占用），其迟到回报不能终结义务。
     * 在手工维保登记事务内调用。
     */
    @Transactional
    public void settleByManualMaintenance(long elevatorId, LocalDate performedDay,
                                          LocalDate dueDay, Instant at) {
        Instant dueAt = dueDay.atStartOfDay(ZoneOffset.UTC).toInstant();
        List<ObligationRow> rows = jdbc.query(
                "SELECT * FROM maintenance_obligation WHERE elevator_id=? AND due_at=? AND status='OPEN' FOR UPDATE",
                OBLIGATION_MAPPER, elevatorId, ElevatorService.ts(dueAt));
        if (rows.isEmpty()) {
            return;
        }
        ObligationRow open = rows.get(0);
        List<Long> attemptIds = jdbc.query(
                "SELECT id FROM maintenance_attempt WHERE obligation_id=? AND status IN ('PLANNED','SUSPENDED') FOR UPDATE",
                (rs, i) -> rs.getLong(1), open.id());
        Instant now = Instant.now(clock);
        for (Long attemptId : attemptIds) {
            jdbc.update("UPDATE maintenance_attempt SET status='ABORTED', version=version+1 WHERE id=?",
                    attemptId);
            appendEvent(attemptId, "ABORTED", "旧手工维保入口已于 " + performedDay
                    + " 结清本周期义务，尝试终止", at);
        }
        jdbc.update("UPDATE maintenance_obligation SET status='FULFILLED', settle_source='MANUAL', "
                        + "completed_attempt_id=NULL, version=version+1, updated_at=? WHERE id=?",
                ElevatorService.ts(now), open.id());
    }

    // ---------------------------------------------------------------- 查询

    @Transactional(readOnly = true)
    public List<ObligationView> obligations(long elevatorId) {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM elevator WHERE id=?", Integer.class, elevatorId);
        if (n == null || n == 0) {
            throw ApiException.notFound("电梯不存在: " + elevatorId);
        }
        return jdbc.query("SELECT * FROM maintenance_obligation WHERE elevator_id=? ORDER BY due_at",
                OBLIGATION_MAPPER, elevatorId).stream().map(MaintenancePlanService::toObligationView).toList();
    }

    @Transactional(readOnly = true)
    public ObligationView obligation(long id) {
        return toObligationView(requireObligation(id));
    }

    @Transactional(readOnly = true)
    public AttemptView attempt(long id) {
        AttemptRow a = getAttempt(id);
        if (a == null) {
            throw ApiException.notFound("履约尝试不存在: " + id);
        }
        return toAttemptView(a);
    }

    @Transactional(readOnly = true)
    public List<AttemptView> attempts(long obligationId) {
        requireObligation(obligationId);
        return jdbc.query("SELECT * FROM maintenance_attempt WHERE obligation_id=? ORDER BY id",
                ATTEMPT_MAPPER, obligationId).stream().map(this::toAttemptView).toList();
    }

    @Transactional(readOnly = true)
    public List<AttemptEventView> attemptTimeline(long attemptId) {
        if (getAttempt(attemptId) == null) {
            throw ApiException.notFound("履约尝试不存在: " + attemptId);
        }
        return jdbc.query("SELECT * FROM maintenance_attempt_event WHERE attempt_id=? ORDER BY id",
                (rs, i) -> new AttemptEventView(rs.getLong("id"), rs.getLong("attempt_id"),
                        rs.getString("event"), rs.getString("detail"),
                        rs.getObject("created_at", LocalDateTime.class).toInstant(ZoneOffset.UTC)),
                attemptId);
    }

    // ---------------------------------------------------------------- 内部

    private static Instant dueAtFor(LocalDate baseDay) {
        return baseDay.plusDays(MaintenanceService.CYCLE_DAYS).atStartOfDay(ZoneOffset.UTC).toInstant();
    }

    private static String fingerprintChecklist(List<MaintenanceItemInput> checklist) {
        List<String> segments = new ArrayList<>();
        for (int i = 0; i < checklist.size(); i++) {
            MaintenanceItemInput item = checklist.get(i);
            segments.add("item[" + i + "].code=" + item.code());
            segments.add("item[" + i + "].name=" + (item.name() == null ? "" : item.name()));
        }
        return Fingerprints.list(segments);
    }

    private ObligationRow lockObligation(long id) {
        List<ObligationRow> rows = jdbc.query(
                "SELECT * FROM maintenance_obligation WHERE id=? FOR UPDATE", OBLIGATION_MAPPER, id);
        if (rows.isEmpty()) {
            throw ApiException.notFound("履约义务不存在: " + id);
        }
        return rows.get(0);
    }

    private ObligationRow requireObligation(long id) {
        List<ObligationRow> rows = jdbc.query(
                "SELECT * FROM maintenance_obligation WHERE id=?", OBLIGATION_MAPPER, id);
        if (rows.isEmpty()) {
            throw ApiException.notFound("履约义务不存在: " + id);
        }
        return rows.get(0);
    }

    private ObligationRow lockOpenObligation(long elevatorId) {
        List<ObligationRow> rows = jdbc.query(
                "SELECT * FROM maintenance_obligation WHERE elevator_id=? AND status='OPEN' ORDER BY due_at DESC "
                        + "FOR UPDATE", OBLIGATION_MAPPER, elevatorId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private AttemptRow lockAttempt(long id) {
        List<AttemptRow> rows = jdbc.query(
                "SELECT * FROM maintenance_attempt WHERE id=? FOR UPDATE", ATTEMPT_MAPPER, id);
        if (rows.isEmpty()) {
            throw ApiException.notFound("履约尝试不存在: " + id);
        }
        return rows.get(0);
    }

    private AttemptRow getAttempt(long id) {
        List<AttemptRow> rows = jdbc.query("SELECT * FROM maintenance_attempt WHERE id=?", ATTEMPT_MAPPER, id);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private void appendEvent(long attemptId, String event, String detail, Instant at) {
        jdbc.update("INSERT INTO maintenance_attempt_event(attempt_id, event, detail, created_at) VALUES (?,?,?,?)",
                attemptId, event, detail, ElevatorService.ts(at));
    }

    private static ObligationView toObligationView(ObligationRow r) {
        return new ObligationView(r.id(), r.elevatorId(), r.baseDay(), r.dueAt(), r.status(),
                r.settleSource(), r.completedAttemptId(), r.version(), r.createdAt(), r.updatedAt());
    }

    private AttemptView toAttemptView(AttemptRow r) {
        List<Long> staffIds = jdbc.query(
                "SELECT staff_id FROM maintenance_attempt_staff WHERE attempt_id=? AND role='ASSIGN' ORDER BY staff_id",
                (rs, i) -> rs.getLong(1), r.id());
        List<MaintenanceItemView> items = jdbc.query(
                "SELECT item_code, item_name, done, done_at FROM maintenance_attempt_item WHERE attempt_id=? ORDER BY id",
                (rs, i) -> new MaintenanceItemView(rs.getString("item_code"), rs.getString("item_name"),
                        rs.getBoolean("done"), toInstant(rs, "done_at")), r.id());
        return new AttemptView(r.id(), r.obligationId(), r.elevatorId(), r.requestId(), r.status(),
                r.windowStart(), r.windowEnd(), r.rescueId(), r.startedAt(), r.suspendedAt(),
                r.completedAt(), r.version(), staffIds, items);
    }

    private static Instant toInstant(java.sql.ResultSet rs, String col) throws java.sql.SQLException {
        LocalDateTime v = rs.getObject(col, LocalDateTime.class);
        return v == null ? null : v.toInstant(ZoneOffset.UTC);
    }
}
