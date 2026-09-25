package com.example.starter.service;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

import com.example.starter.domain.ChainAction;
import com.example.starter.domain.MaintenanceResult;
import com.example.starter.domain.RescueStatus;
import com.example.starter.domain.StopReason;
import com.example.starter.error.ApiException;
import com.example.starter.service.ElevatorService.ElevatorRow;
import com.example.starter.web.dto.ApiDtos.MaintenanceRequest;
import com.example.starter.web.dto.ApiDtos.MaintenanceView;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 维保台账：周期 15 日（UTC 日界），恰第 15 日完成合规；
 * 逾期锁定电梯并列入重点监察；结案后迟到维保仅记迟到事件。
 */
@Service
public class MaintenanceService {

    /** 维保周期（日） */
    static final long CYCLE_DAYS = 15;

    private final JdbcTemplate jdbc;
    private final ElevatorService elevatorService;
    private final IdempotencyService idempotency;
    private final Clock clock;

    public MaintenanceService(JdbcTemplate jdbc, ElevatorService elevatorService,
                              IdempotencyService idempotency, Clock clock) {
        this.jdbc = jdbc;
        this.elevatorService = elevatorService;
        this.idempotency = idempotency;
        this.clock = clock;
    }

    public ResponseEntity<String> register(MaintenanceRequest req) {
        String fp = Fingerprints.of("scope=MAINTENANCE", "requestId=" + req.requestId(),
                "elevatorId=" + req.elevatorId(), "performedDay=" + req.performedDay(),
                "rescueRequestId=" + req.rescueRequestId(), "note=" + req.note());
        return idempotency.handle("MAINTENANCE", req.requestId(), fp, () -> doRegister(req));
    }

    private MaintenanceView doRegister(MaintenanceRequest req) {
        ElevatorRow ev = elevatorService.lockElevator(req.elevatorId());
        LocalDate performedDay = req.performedDay();

        // 结案后迟到的维保记录：只记迟到事件，不变更任何状态
        if (req.rescueRequestId() != null) {
            List<String> status = jdbc.query("SELECT status FROM rescue_order WHERE request_id=?",
                    (rs, i) -> rs.getString(1), req.rescueRequestId());
            if (!status.isEmpty() && RescueStatus.CLOSED.name().equals(status.get(0))) {
                return insertRecord(ev.id(), req, null, MaintenanceResult.LATE_EVENT,
                        "关联救援单已结案，迟到维保仅记事件");
            }
        }
        // 乱序/重复补录：执行日未晚于最近有效维保日，仅记迟到事件
        if (ev.lastMaintenanceDay() != null && !performedDay.isAfter(ev.lastMaintenanceDay())) {
            return insertRecord(ev.id(), req, null, MaintenanceResult.LATE_EVENT,
                    "执行日不晚于最近维保日，按迟到事件记录");
        }

        LocalDate base = ev.lastMaintenanceDay() != null ? ev.lastMaintenanceDay() : ev.commissionedDay();
        LocalDate dueDay = base.plusDays(CYCLE_DAYS);
        if (performedDay.isAfter(dueDay)) {
            // 逾期：锁定电梯并列入重点监察；维保事实仍入账
            jdbc.update("UPDATE elevator SET key_supervision=TRUE, last_maintenance_day=? WHERE id=?",
                    performedDay, ev.id());
            MaintenanceView v = insertRecord(ev.id(), req, dueDay, MaintenanceResult.OVERDUE, req.note());
            boolean alreadyLocked = elevatorService.openStops(ev.id()).stream()
                    .anyMatch(s -> StopReason.MAINTENANCE_OVERDUE.name().equals(s.reason()));
            if (!alreadyLocked) {
                Instant now = Instant.now(clock);
                if ("RUNNING".equals(ev.status())) {
                    elevatorService.stopIfRunning(ev, StopReason.MAINTENANCE_OVERDUE, null, now);
                } else {
                    // 已停梯（如在途救援）：补落逾期锁定链，形成唯一终态
                    elevatorService.appendChain(ev.id(), ChainAction.STOP, StopReason.MAINTENANCE_OVERDUE, null, now);
                }
            }
            return v;
        }
        // 周期内（含恰第 15 日）：合规
        jdbc.update("UPDATE elevator SET last_maintenance_day=? WHERE id=?", performedDay, ev.id());
        return insertRecord(ev.id(), req, dueDay, MaintenanceResult.COMPLIANT, req.note());
    }

    private MaintenanceView insertRecord(long elevatorId, MaintenanceRequest req, LocalDate dueDay,
                                         MaintenanceResult result, String note) {
        jdbc.update("INSERT INTO maintenance_record(elevator_id, request_id, performed_day, due_day, result, note) "
                        + "VALUES (?,?,?,?,?,?)",
                elevatorId, req.requestId(), req.performedDay(), dueDay, result.name(), note);
        long id = jdbc.queryForObject("SELECT MAX(id) FROM maintenance_record WHERE elevator_id=?",
                Long.class, elevatorId);
        return jdbc.queryForObject("SELECT * FROM maintenance_record WHERE id=?", this::mapView, id);
    }

    @Transactional(readOnly = true)
    public List<MaintenanceView> ledger(long elevatorId) {
        return jdbc.query("SELECT * FROM maintenance_record WHERE elevator_id=? ORDER BY id",
                this::mapView, elevatorId);
    }

    private MaintenanceView mapView(java.sql.ResultSet rs, int i) throws java.sql.SQLException {
        return new MaintenanceView(rs.getLong("id"), rs.getLong("elevator_id"),
                rs.getObject("performed_day", LocalDate.class),
                rs.getObject("due_day", LocalDate.class),
                rs.getString("result"), rs.getString("note"),
                rs.getObject("created_at", LocalDateTime.class).toInstant(ZoneOffset.UTC));
    }
}
