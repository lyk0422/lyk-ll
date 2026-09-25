package com.example.starter.service;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

import com.example.starter.domain.ChainAction;
import com.example.starter.domain.StopReason;
import com.example.starter.error.ApiException;
import com.example.starter.web.dto.ApiDtos.ChainEntryView;
import com.example.starter.web.dto.ApiDtos.ElevatorRecheckRequest;
import com.example.starter.web.dto.ApiDtos.ElevatorRequest;
import com.example.starter.web.dto.ApiDtos.ElevatorView;
import com.example.starter.web.dto.ApiDtos.StaffRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 电梯台账、人员台账与停复梯链共享逻辑。
 * 停梯/复梯只通过本服务落链，保证链可复算且状态一致。
 */
@Service
public class ElevatorService {

    /** 电梯行快照 */
    public record ElevatorRow(long id, String code, String name, String status, boolean keySupervision,
                              LocalDate lastMaintenanceDay, LocalDate commissionedDay) {
    }

    /** 停复梯链行 */
    public record ChainRow(long id, String action, String reason, Long rescueId, Instant createdAt) {
    }

    static final RowMapper<ElevatorRow> ELEVATOR_MAPPER = (rs, i) -> new ElevatorRow(
            rs.getLong("id"), rs.getString("code"), rs.getString("name"), rs.getString("status"),
            rs.getBoolean("key_supervision"),
            rs.getObject("last_maintenance_day", LocalDate.class),
            rs.getObject("commissioned_day", LocalDate.class));

    static final RowMapper<ChainRow> CHAIN_MAPPER = (rs, i) -> new ChainRow(
            rs.getLong("id"), rs.getString("action"), rs.getString("reason"),
            rs.getObject("rescue_id", Long.class),
            rs.getObject("created_at", LocalDateTime.class).toInstant(ZoneOffset.UTC));

    private final JdbcTemplate jdbc;
    private final Clock clock;

    public ElevatorService(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Transactional
    public ElevatorView createElevator(ElevatorRequest req) {
        Instant now = Instant.now(clock);
        jdbc.update("INSERT INTO elevator(code, name, commissioned_day, created_at) VALUES (?,?,?,?)",
                req.code(), req.name(), req.commissionedDay(), ts(now));
        long id = jdbc.queryForObject("SELECT id FROM elevator WHERE code=?", Long.class, req.code());
        return toView(lockElevator(id));
    }

    @Transactional
    public long createStaff(StaffRequest req) {
        jdbc.update("INSERT INTO staff(name, cert_no, certified) VALUES (?,?,?)",
                req.name(), req.certNo(), req.certified());
        return jdbc.queryForObject("SELECT id FROM staff WHERE cert_no=?", Long.class, req.certNo());
    }

    @Transactional(readOnly = true)
    public ElevatorView getElevator(long id) {
        List<ElevatorRow> rows = jdbc.query("SELECT * FROM elevator WHERE id=?", ELEVATOR_MAPPER, id);
        if (rows.isEmpty()) {
            throw ApiException.notFound("电梯不存在: " + id);
        }
        return toView(rows.get(0));
    }

    @Transactional(readOnly = true)
    public List<ChainEntryView> chain(long elevatorId) {
        return jdbc.query("SELECT * FROM stop_resume_chain WHERE elevator_id=? ORDER BY id", CHAIN_MAPPER, elevatorId)
                .stream().map(ElevatorService::toChainView).toList();
    }

    /**
     * 电梯级复检（维保逾期锁定/强制停梯的复梯入口）：
     * 复检合格与恢复运行在同一事务内完成；存在进行中救援单时须走救援复检。
     */
    @Transactional
    public ElevatorView recheckElevator(long elevatorId, ElevatorRecheckRequest req) {
        ElevatorRow ev = lockElevator(elevatorId);
        if (!"STOPPED".equals(ev.status())) {
            throw ApiException.conflict("NOT_STOPPED", "电梯未处于停梯状态，无需复检复梯");
        }
        if (hasActiveRescue(elevatorId)) {
            throw ApiException.conflict("RESCUE_IN_FLIGHT", "存在进行中的救援单，须通过救援复检恢复运行");
        }
        if (!req.inspectionPassed()) {
            throw ApiException.unprocessable("INSPECTION_FAILED", "复检不合格，禁止恢复运行");
        }
        Instant now = Instant.now(clock);
        jdbc.update("UPDATE elevator SET status='RUNNING' WHERE id=?", elevatorId);
        appendChain(elevatorId, ChainAction.RESUME, StopReason.RECHECK_PASS, null, now);
        return toView(lockElevator(elevatorId));
    }

    /** 锁定电梯行（FOR UPDATE），不存在则 404。 */
    public ElevatorRow lockElevator(long id) {
        List<ElevatorRow> rows = jdbc.query("SELECT * FROM elevator WHERE id=? FOR UPDATE", ELEVATOR_MAPPER, id);
        if (rows.isEmpty()) {
            throw ApiException.notFound("电梯不存在: " + id);
        }
        return rows.get(0);
    }

    /** 是否存在进行中的救援单。 */
    public boolean hasActiveRescue(long elevatorId) {
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM rescue_order WHERE active_elevator_key=?", Integer.class, elevatorId);
        return n != null && n > 0;
    }

    /** 未闭合的停梯记录（最近一次复梯之后的全部 STOP）。 */
    public List<ChainRow> openStops(long elevatorId) {
        return jdbc.query(
                "SELECT * FROM stop_resume_chain WHERE elevator_id=? AND action='STOP' AND id > "
                        + "COALESCE((SELECT MAX(id) FROM stop_resume_chain WHERE elevator_id=? AND action='RESUME'), 0) "
                        + "ORDER BY id",
                CHAIN_MAPPER, elevatorId, elevatorId);
    }

    /** 追加停复梯链条目。 */
    public void appendChain(long elevatorId, ChainAction action, StopReason reason, Long rescueId, Instant at) {
        jdbc.update("INSERT INTO stop_resume_chain(elevator_id, action, reason, rescue_id, created_at) VALUES (?,?,?,?,?)",
                elevatorId, action.name(), reason.name(), rescueId, ts(at));
    }

    /** 若电梯在运则停梯并落链；已停梯则仅按需补落链（由调用方决定）。 */
    public void stopIfRunning(ElevatorRow ev, StopReason reason, Long rescueId, Instant at) {
        if ("RUNNING".equals(ev.status())) {
            jdbc.update("UPDATE elevator SET status='STOPPED' WHERE id=?", ev.id());
            appendChain(ev.id(), ChainAction.STOP, reason, rescueId, at);
        }
    }

    static ElevatorView toView(ElevatorRow r) {
        return new ElevatorView(r.id(), r.code(), r.name(), r.status(), r.keySupervision(), r.lastMaintenanceDay());
    }

    static ChainEntryView toChainView(ChainRow r) {
        return new ChainEntryView(r.id(), r.action(), r.reason(), r.rescueId(), r.createdAt());
    }

    static LocalDateTime ts(Instant instant) {
        return LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
