package com.example.starter.service;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.stream.Collectors;

import com.example.starter.domain.ChainAction;
import com.example.starter.domain.RescueStatus;
import com.example.starter.domain.StopReason;
import com.example.starter.error.ApiException;
import com.example.starter.service.ElevatorService.ChainRow;
import com.example.starter.service.ElevatorService.ElevatorRow;
import com.example.starter.web.dto.ApiDtos.ArriveRequest;
import com.example.starter.web.dto.ApiDtos.CloseRequest;
import com.example.starter.web.dto.ApiDtos.DispatchRequest;
import com.example.starter.web.dto.ApiDtos.EventView;
import com.example.starter.web.dto.ApiDtos.RecheckRequest;
import com.example.starter.web.dto.ApiDtos.ReportRequest;
import com.example.starter.web.dto.ApiDtos.RescueRequest;
import com.example.starter.web.dto.ApiDtos.RescueView;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 救援主流程：接报→派单→到场→施救→复检恢复→回访结案。
 * 接报/派单/结案以 requestId 幂等；到场满 30 分钟判超时升级；
 * 90 日（UTC 日界）内第 2 次困人强制停梯；复检合格与复梯同事务。
 */
@Service
public class RescueService {

    /** 到场时限：接报后 30 分钟，恰满即超时 */
    static final long ARRIVAL_LIMIT_MINUTES = 30;
    /** 困人统计窗口：90 日（UTC 日界），含当日 */
    static final long INCIDENT_WINDOW_DAYS = 90;

    /** 救援单行 */
    public record RescueRow(long id, long elevatorId, String requestId, String status,
                            Instant reportedAt, LocalDate reportedDay) {
    }

    static final RowMapper<RescueRow> RESCUE_MAPPER = (rs, i) -> new RescueRow(
            rs.getLong("id"), rs.getLong("elevator_id"), rs.getString("request_id"), rs.getString("status"),
            rs.getObject("reported_at", LocalDateTime.class).toInstant(ZoneOffset.UTC),
            rs.getObject("reported_day", LocalDate.class));

    private final JdbcTemplate jdbc;
    private final ElevatorService elevatorService;
    private final IdempotencyService idempotency;
    private final ObligationService obligationService;
    private final Clock clock;

    public RescueService(JdbcTemplate jdbc, ElevatorService elevatorService,
                         IdempotencyService idempotency, ObligationService obligationService, Clock clock) {
        this.jdbc = jdbc;
        this.elevatorService = elevatorService;
        this.idempotency = idempotency;
        this.obligationService = obligationService;
        this.clock = clock;
    }

    // ---------------------------------------------------------------- 接报

    public ResponseEntity<String> report(ReportRequest req) {
        String fp = Fingerprints.of("scope=REPORT", "requestId=" + req.requestId(),
                "elevatorId=" + req.elevatorId(), "reportedAt=" + req.reportedAt());
        return idempotency.handle("REPORT", req.requestId(), fp, () -> doReport(req));
    }

    private RescueView doReport(ReportRequest req) {
        ElevatorRow ev = elevatorService.lockElevator(req.elevatorId());
        // 并发同键重放：胜出方已提交，交由幂等记录仲裁返回首次结果
        List<Long> existing = jdbc.query("SELECT id FROM rescue_order WHERE request_id=?",
                (rs, i) -> rs.getLong(1), req.requestId());
        if (!existing.isEmpty()) {
            return view(existing.get(0));
        }
        Instant reportedAt = req.reportedAt() != null ? req.reportedAt() : Instant.now(clock);
        LocalDate day = reportedAt.atZone(ZoneOffset.UTC).toLocalDate();
        if (elevatorService.hasActiveRescue(ev.id())) {
            throw ApiException.conflict("ACTIVE_RESCUE_EXISTS", "同一电梯已存在进行中的救援单，唯一受理");
        }
        jdbc.update("INSERT INTO rescue_order(elevator_id, request_id, fingerprint, status, reported_at, reported_day, "
                        + "active_elevator_key, created_at) VALUES (?,?,?,'REPORTED',?,?,?,?)",
                ev.id(), req.requestId(), "report", ElevatorService.ts(reportedAt),
                day, ev.id(), ElevatorService.ts(Instant.now(clock)));
        long rescueId = jdbc.queryForObject("SELECT id FROM rescue_order WHERE request_id=?", Long.class, req.requestId());
        appendEvent(rescueId, "REPORTED", "接报困人", reportedAt);
        // 困人即停梯；已停梯（如维保锁定）则不重复落 RESCUE 停梯链
        elevatorService.stopIfRunning(ev, StopReason.RESCUE, rescueId, reportedAt);
        // 接报原子挂起该电梯未完成的履约维保尝试并释放人员，保留已做清单，不改变到期义务
        obligationService.suspendActiveForRescue(ev.id(), rescueId, reportedAt);
        // 90 日（UTC 日界）内第 2 次困人：强制停梯检查
        LocalDate windowStart = day.minusDays(INCIDENT_WINDOW_DAYS - 1);
        Integer incidents = jdbc.queryForObject(
                "SELECT COUNT(*) FROM rescue_order WHERE elevator_id=? AND reported_day >= ?",
                Integer.class, ev.id(), windowStart);
        if (incidents != null && incidents >= 2) {
            boolean alreadyForced = elevatorService.openStops(ev.id()).stream()
                    .anyMatch(s -> StopReason.FORCE_STOP_90D.name().equals(s.reason()));
            if (!alreadyForced) {
                elevatorService.appendChain(ev.id(), ChainAction.STOP, StopReason.FORCE_STOP_90D, rescueId, reportedAt);
                appendEvent(rescueId, "ESCALATED", "90日内第2次困人，强制停梯检查", reportedAt);
            }
        }
        return view(rescueId);
    }

    // ---------------------------------------------------------------- 派单

    public ResponseEntity<String> dispatch(long rescueId, DispatchRequest req) {
        List<Long> sorted = req.staffIds().stream().distinct().sorted().toList();
        String fp = Fingerprints.of("scope=DISPATCH", "requestId=" + req.requestId(),
                "rescueId=" + rescueId, "staffIds=" + sorted.stream().map(String::valueOf)
                        .collect(Collectors.joining(",")));
        return idempotency.handle("DISPATCH", req.requestId(), fp, () -> doDispatch(rescueId, req.requestId(), sorted));
    }

    private RescueView doDispatch(long rescueId, String requestId, List<Long> staffIds) {
        RescueRow r = lockRescue(rescueId);
        if (idempotency.exists("DISPATCH", requestId)) {
            // 并发同键重放：交由幂等记录仲裁返回首次结果
            return view(rescueId);
        }
        if (!RescueStatus.REPORTED.name().equals(r.status())) {
            throw ApiException.conflict("ILLEGAL_STATE", "当前状态不允许派单: " + r.status());
        }
        ElevatorRow ev = elevatorService.lockElevator(r.elevatorId());
        for (Long staffId : staffIds) {
            Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM staff WHERE id=?", Integer.class, staffId);
            if (n == null || n == 0) {
                throw ApiException.notFound("人员不存在: " + staffId);
            }
        }
        // 停梯派单竞态的唯一终态：存在与本单无关的未闭合停梯（维保锁定等）时禁止派单
        boolean blocked = elevatorService.openStops(ev.id()).stream()
                .anyMatch(s -> s.rescueId() == null || s.rescueId() != rescueId);
        if (blocked) {
            throw ApiException.conflict("ELEVATOR_LOCKED", "电梯已停梯锁定，不得既停梯又继续派单");
        }
        Instant now = Instant.now(clock);
        jdbc.update("UPDATE rescue_order SET status='DISPATCHED', dispatched_at=? WHERE id=?",
                ElevatorService.ts(now), rescueId);
        for (Long staffId : staffIds) {
            jdbc.update("INSERT INTO rescue_staff(rescue_id, staff_id, role) VALUES (?,?,'DISPATCH')", rescueId, staffId);
        }
        appendEvent(rescueId, "DISPATCHED", "派单: " + staffIds, now);
        return view(rescueId);
    }

    // ---------------------------------------------------------------- 到场

    @Transactional
    public RescueView arrive(long rescueId, ArriveRequest req) {
        RescueRow r = lockRescue(rescueId);
        if (!RescueStatus.DISPATCHED.name().equals(r.status())) {
            throw ApiException.conflict("ILLEGAL_STATE", "当前状态不允许到场登记: " + r.status());
        }
        Instant arrivedAt = req.arrivedAt() != null ? req.arrivedAt() : Instant.now(clock);
        boolean timeout = !arrivedAt.isBefore(r.reportedAt().plus(ARRIVAL_LIMIT_MINUTES, ChronoUnit.MINUTES));
        jdbc.update("UPDATE rescue_order SET status='ARRIVED', arrived_at=?, timeout_escalated=?, escalated_at=? WHERE id=?",
                ElevatorService.ts(arrivedAt), timeout, timeout ? ElevatorService.ts(arrivedAt) : null, rescueId);
        appendEvent(rescueId, "ARRIVED", "到场", arrivedAt);
        if (timeout) {
            appendEvent(rescueId, "ESCALATED", "接报后满30分钟未到场，超时升级通报", arrivedAt);
        }
        return view(rescueId);
    }

    // ---------------------------------------------------------------- 施救

    @Transactional
    public RescueView rescue(long rescueId, RescueRequest req) {
        RescueRow r = lockRescue(rescueId);
        if (!RescueStatus.ARRIVED.name().equals(r.status())) {
            throw ApiException.conflict("ILLEGAL_STATE", "当前状态不允许施救登记: " + r.status());
        }
        List<Long> ids = req.staffIds().stream().distinct().sorted().toList();
        if (ids.size() != 2) {
            throw ApiException.unprocessable("TWO_RESCUERS_REQUIRED", "施救须两名持证人员，单人操作不生效");
        }
        for (Long staffId : ids) {
            List<Boolean> certified = jdbc.query("SELECT certified FROM staff WHERE id=?",
                    (rs, i) -> rs.getBoolean(1), staffId);
            if (certified.isEmpty()) {
                throw ApiException.notFound("人员不存在: " + staffId);
            }
            if (!certified.get(0)) {
                throw ApiException.unprocessable("UNQUALIFIED_STAFF", "施救人员未持有效证书: " + staffId);
            }
        }
        Instant at = req.rescuedAt() != null ? req.rescuedAt() : Instant.now(clock);
        jdbc.update("UPDATE rescue_order SET status='RESCUED', rescued_at=? WHERE id=?",
                ElevatorService.ts(at), rescueId);
        for (Long staffId : ids) {
            jdbc.update("INSERT INTO rescue_staff(rescue_id, staff_id, role) VALUES (?,?,'RESCUE')", rescueId, staffId);
        }
        appendEvent(rescueId, "RESCUED", "两名持证人员完成施救: " + ids, at);
        return view(rescueId);
    }

    // ---------------------------------------------------------------- 复检恢复

    @Transactional
    public RescueView recheck(long rescueId, RecheckRequest req) {
        RescueRow r = lockRescue(rescueId);
        if (!RescueStatus.RESCUED.name().equals(r.status())) {
            throw ApiException.conflict("ILLEGAL_STATE", "当前状态不允许复检: " + r.status());
        }
        if (!req.inspectionPassed()) {
            throw ApiException.unprocessable("INSPECTION_FAILED", "复检不合格，禁止恢复运行");
        }
        Instant at = req.recheckedAt() != null ? req.recheckedAt() : Instant.now(clock);
        ElevatorRow ev = elevatorService.lockElevator(r.elevatorId());
        jdbc.update("UPDATE rescue_order SET status='RESTORED', restored_at=? WHERE id=?",
                ElevatorService.ts(at), rescueId);
        appendEvent(rescueId, "RESTORED", "复检合格", at);
        // 复检合格与恢复运行同一事务：仅当未闭合停梯全部由本单引起时才复梯
        List<ChainRow> open = elevatorService.openStops(ev.id());
        boolean allMine = open.stream().allMatch(s -> s.rescueId() != null && s.rescueId() == rescueId);
        if (allMine) {
            jdbc.update("UPDATE elevator SET status='RUNNING' WHERE id=?", ev.id());
            elevatorService.appendChain(ev.id(), ChainAction.RESUME, StopReason.RECHECK_PASS, rescueId, at);
            appendEvent(rescueId, "RESUMED", "复检合格，恢复运行", at);
        }
        return view(rescueId);
    }

    // ---------------------------------------------------------------- 回访结案

    public ResponseEntity<String> close(long rescueId, CloseRequest req) {
        String fp = Fingerprints.of("scope=CLOSE", "requestId=" + req.requestId(),
                "rescueId=" + rescueId, "closedAt=" + req.closedAt());
        return idempotency.handle("CLOSE", req.requestId(), fp, () -> doClose(rescueId, req));
    }

    private RescueView doClose(long rescueId, CloseRequest req) {
        RescueRow r = lockRescue(rescueId);
        if (RescueStatus.CLOSED.name().equals(r.status())) {
            // 结案为终态：重复结案返回幂等成功
            return view(rescueId);
        }
        if (!RescueStatus.RESTORED.name().equals(r.status())) {
            throw ApiException.conflict("ILLEGAL_STATE", "当前状态不允许结案: " + r.status());
        }
        Instant at = req.closedAt() != null ? req.closedAt() : Instant.now(clock);
        jdbc.update("UPDATE rescue_order SET status='CLOSED', closed_at=?, active_elevator_key=NULL WHERE id=?",
                ElevatorService.ts(at), rescueId);
        appendEvent(rescueId, "CLOSED", "回访结案", at);
        return view(rescueId);
    }

    // ---------------------------------------------------------------- 查询

    @Transactional(readOnly = true)
    public RescueView getRescue(long rescueId) {
        return view(rescueId);
    }

    @Transactional(readOnly = true)
    public List<EventView> timeline(long rescueId) {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM rescue_order WHERE id=?", Integer.class, rescueId);
        if (n == null || n == 0) {
            throw ApiException.notFound("救援单不存在: " + rescueId);
        }
        return jdbc.query("SELECT * FROM rescue_event WHERE rescue_id=? ORDER BY id",
                (rs, i) -> new EventView(rs.getLong("id"), rs.getString("event"), rs.getString("detail"),
                        rs.getObject("created_at", LocalDateTime.class).toInstant(ZoneOffset.UTC)),
                rescueId);
    }

    // ---------------------------------------------------------------- 内部

    private RescueRow lockRescue(long rescueId) {
        List<RescueRow> rows = jdbc.query("SELECT * FROM rescue_order WHERE id=? FOR UPDATE", RESCUE_MAPPER, rescueId);
        if (rows.isEmpty()) {
            throw ApiException.notFound("救援单不存在: " + rescueId);
        }
        return rows.get(0);
    }

    private RescueView view(long rescueId) {
        return jdbc.queryForObject(
                "SELECT * FROM rescue_order WHERE id=?",
                (rs, i) -> new RescueView(rs.getLong("id"), rs.getLong("elevator_id"), rs.getString("request_id"),
                        rs.getString("status"), toInstant(rs, "reported_at"), toInstant(rs, "dispatched_at"),
                        toInstant(rs, "arrived_at"), toInstant(rs, "rescued_at"), toInstant(rs, "restored_at"),
                        toInstant(rs, "closed_at"), rs.getBoolean("timeout_escalated"),
                        toInstant(rs, "escalated_at")),
                rescueId);
    }

    private static Instant toInstant(java.sql.ResultSet rs, String col) throws java.sql.SQLException {
        LocalDateTime v = rs.getObject(col, LocalDateTime.class);
        return v == null ? null : v.toInstant(ZoneOffset.UTC);
    }

    private void appendEvent(long rescueId, String event, String detail, Instant at) {
        jdbc.update("INSERT INTO rescue_event(rescue_id, event, detail, created_at) VALUES (?,?,?,?)",
                rescueId, event, detail, ElevatorService.ts(at));
    }
}
