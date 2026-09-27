package com.example.starter.groundwire;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.starter.error.ApiException;
import com.example.starter.groundwire.GroundWireDtos.ApplyRequest;
import com.example.starter.groundwire.GroundWireDtos.BorrowItem;
import com.example.starter.groundwire.GroundWireDtos.BorrowRequest;
import com.example.starter.groundwire.GroundWireDtos.BorrowView;
import com.example.starter.groundwire.GroundWireDtos.GroundWireDetailView;
import com.example.starter.groundwire.GroundWireDtos.GroundWireEventView;
import com.example.starter.groundwire.GroundWireDtos.GroundWireView;
import com.example.starter.groundwire.GroundWireDtos.LoanView;
import com.example.starter.groundwire.GroundWireDtos.RemoveRequest;
import com.example.starter.groundwire.GroundWireDtos.ReturnRequest;
import com.example.starter.interlock.InterlockResult;
import com.example.starter.interlock.InterlockService;

/**
 * 临时接地线借用核销服务：借用、挂接、拆除、归还与查询。
 * 状态机：AVAILABLE → BORROWED → APPLIED → REMOVED_PENDING_RETURN → AVAILABLE；
 * 版本从1开始，每次成功变更加1；挂接/拆除双人确认并保存不可覆盖证据；
 * 送电门禁在事务内复查间隔全部临时接地线均已拆除。
 */
@Service
public class GroundWireService {

    /** 送电门禁闭锁规则编码：间隔内仍有临时接地线挂接。 */
    public static final String TEMP_GROUND_ENERGIZE = "TEMP_GROUND_ENERGIZE";

    private static final String ST_AVAILABLE = "AVAILABLE";
    private static final String ST_BORROWED = "BORROWED";
    private static final String ST_APPLIED = "APPLIED";
    private static final String ST_REMOVED_PENDING_RETURN = "REMOVED_PENDING_RETURN";

    private static final RowMapper<WireRow> WIRE_MAPPER = (rs, rowNum) -> new WireRow(
            rs.getLong("id"),
            rs.getString("wire_no"),
            rs.getString("status"),
            rs.getInt("version"),
            rs.getObject("ticket_id") == null ? null : rs.getLong("ticket_id"),
            rs.getString("interval_id"),
            toInstant(rs.getTimestamp("created_at")),
            toInstant(rs.getTimestamp("updated_at")));

    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final InterlockService interlockService;

    public GroundWireService(JdbcTemplate jdbc, Clock businessClock, InterlockService interlockService) {
        this.jdbc = jdbc;
        this.clock = businessClock;
        this.interlockService = interlockService;
    }

    /**
     * 批量借用：绑定未归档且未作废的操作票及间隔；任一接地线不可用整批拒绝。
     */
    @Transactional
    public BorrowView borrow(BorrowRequest req) {
        TicketRow ticket = mustLoadTicket(req.ticketId());
        assertTicketBorrowable(ticket);
        if (!ticket.intervalId().equals(req.intervalId())) {
            throw ApiException.unprocessable("BORROW_INTERVAL_MISMATCH",
                    "借用间隔 " + req.intervalId() + " 与操作票间隔 " + ticket.intervalId() + " 不一致");
        }
        Set<String> seen = new HashSet<>();
        for (BorrowItem item : req.wires()) {
            if (!seen.add(item.wireNo())) {
                throw ApiException.badRequest("DUPLICATE_WIRE", "同一批次重复借用接地线: " + item.wireNo());
            }
        }
        // 先整批校验，任一不可用整批拒绝
        List<WireRow> wires = new ArrayList<>();
        for (BorrowItem item : req.wires()) {
            WireRow wire = mustLoadWire(item.wireNo());
            if (!ST_AVAILABLE.equals(wire.status())) {
                throw ApiException.unprocessable("WIRE_NOT_AVAILABLE",
                        "接地线 " + item.wireNo() + " 当前状态 " + wire.status() + "，不可借用，整批拒绝");
            }
            if (wire.version() != item.expectedVersion()) {
                throw ApiException.conflict("VERSION_CONFLICT",
                        "接地线 " + item.wireNo() + " 版本冲突：期望 " + item.expectedVersion()
                                + "，当前 " + wire.version());
            }
            wires.add(wire);
        }
        Instant now = req.operatedAt() != null ? req.operatedAt() : Instant.now(clock);
        List<GroundWireView> borrowed = new ArrayList<>();
        for (WireRow wire : wires) {
            int updated = jdbc.update(
                    "UPDATE ground_wire SET status = ?, version = version + 1, ticket_id = ?, interval_id = ?,"
                            + " updated_at = ? WHERE id = ? AND version = ? AND status = ?",
                    ST_BORROWED, req.ticketId(), req.intervalId(), Timestamp.from(now),
                    wire.id(), wire.version(), ST_AVAILABLE);
            if (updated == 0) {
                throw ApiException.conflict("VERSION_CONFLICT",
                        "接地线 " + wire.wireNo() + " 并发变更，整批拒绝");
            }
            jdbc.update("INSERT INTO ground_wire_loan (wire_id, ticket_id, interval_id, borrowed_at)"
                            + " VALUES (?, ?, ?, ?)",
                    wire.id(), req.ticketId(), req.intervalId(), Timestamp.from(now));
            borrowed.add(toView(mustLoadWire(wire.wireNo())));
        }
        return new BorrowView(req.ticketId(), req.intervalId(), borrowed);
    }

    /**
     * 挂接：引用本票步骤，操作人与监护人两名不同人员共同确认，保存不可覆盖证据。
     */
    @Transactional
    public GroundWireView apply(String wireNo, ApplyRequest req) {
        WireRow wire = mustLoadWire(wireNo);
        assertDoubleConfirm(req.operatorId(), req.guardianId());
        assertBoundToTicket(wire, req.ticketId());
        assertStepExists(req.ticketId(), req.stepSeq());
        if (!ST_BORROWED.equals(wire.status())) {
            throw ApiException.unprocessable("WIRE_STATE",
                    "接地线 " + wireNo + " 当前状态 " + wire.status() + "，仅借出状态可挂接");
        }
        assertVersion(wire, req.expectedVersion());
        // 与送电门禁互斥：锁定间隔执行锁行，防止挂接与送电并发都通过
        lockIntervalRow(wire.intervalId());
        InterlockResult live = interlockService.evaluateLiveGround(wire.intervalId());
        if (live.blocked()) {
            interlockService.recordHit(req.ticketId(), req.stepSeq(), live.ruleCode(), live.detail());
            throw ApiException.unprocessable("INTERLOCK_BLOCKED",
                    live.detail() + " [" + live.ruleCode() + "]");
        }
        Instant now = req.operatedAt() != null ? req.operatedAt() : Instant.now(clock);
        int updated = jdbc.update(
                "UPDATE ground_wire SET status = ?, version = version + 1, updated_at = ?"
                        + " WHERE id = ? AND version = ? AND status = ?",
                ST_APPLIED, Timestamp.from(now), wire.id(), req.expectedVersion(), ST_BORROWED);
        if (updated == 0) {
            throw ApiException.conflict("VERSION_CONFLICT", "接地线 " + wireNo + " 并发变更，挂接失败");
        }
        insertEvent(wire.id(), req.ticketId(), req.stepSeq(), "APPLY",
                req.operatorId(), req.guardianId(), now);
        return toView(mustLoadWire(wireNo));
    }

    /**
     * 拆除：引用本票步骤，双人确认，保存不可覆盖证据；拆除后待归还。
     * 票作废或异常不自动恢复可用，仍须本拆除与归还流程。
     */
    @Transactional
    public GroundWireView remove(String wireNo, RemoveRequest req) {
        WireRow wire = mustLoadWire(wireNo);
        assertDoubleConfirm(req.operatorId(), req.guardianId());
        assertBoundToTicket(wire, req.ticketId());
        assertStepExists(req.ticketId(), req.stepSeq());
        if (!ST_APPLIED.equals(wire.status())) {
            throw ApiException.unprocessable("WIRE_STATE",
                    "接地线 " + wireNo + " 当前状态 " + wire.status() + "，仅已挂接状态可拆除");
        }
        assertVersion(wire, req.expectedVersion());
        Instant now = req.operatedAt() != null ? req.operatedAt() : Instant.now(clock);
        int updated = jdbc.update(
                "UPDATE ground_wire SET status = ?, version = version + 1, updated_at = ?"
                        + " WHERE id = ? AND version = ? AND status = ?",
                ST_REMOVED_PENDING_RETURN, Timestamp.from(now), wire.id(), req.expectedVersion(), ST_APPLIED);
        if (updated == 0) {
            throw ApiException.conflict("VERSION_CONFLICT", "接地线 " + wireNo + " 并发变更，拆除失败");
        }
        insertEvent(wire.id(), req.ticketId(), req.stepSeq(), "REMOVE",
                req.operatorId(), req.guardianId(), now);
        return toView(mustLoadWire(wireNo));
    }

    /**
     * 归还核销：仓管核对编号，仅核销已拆除实物；归还后实物恢复可用，借用历史保留。
     */
    @Transactional
    public GroundWireView returnWire(String wireNo, ReturnRequest req) {
        WireRow wire = mustLoadWire(wireNo);
        if (!ST_REMOVED_PENDING_RETURN.equals(wire.status())) {
            throw ApiException.unprocessable("WIRE_STATE",
                    "接地线 " + wireNo + " 当前状态 " + wire.status() + "，仅已拆除待归还可核销");
        }
        assertVersion(wire, req.expectedVersion());
        Instant now = req.operatedAt() != null ? req.operatedAt() : Instant.now(clock);
        int updated = jdbc.update(
                "UPDATE ground_wire SET status = ?, version = version + 1, ticket_id = NULL, interval_id = NULL,"
                        + " updated_at = ? WHERE id = ? AND version = ? AND status = ?",
                ST_AVAILABLE, Timestamp.from(now), wire.id(), req.expectedVersion(), ST_REMOVED_PENDING_RETURN);
        if (updated == 0) {
            throw ApiException.conflict("VERSION_CONFLICT", "接地线 " + wireNo + " 并发变更，归还失败");
        }
        jdbc.update("UPDATE ground_wire_loan SET returned_at = ?, keeper_id = ?"
                        + " WHERE wire_id = ? AND returned_at IS NULL",
                Timestamp.from(now), req.keeperId(), wire.id());
        return toView(mustLoadWire(wireNo));
    }

    /**
     * 送电门禁：送电类步确认时在事务内重新检查该间隔所有临时接地线均已拆除，
     * 任何仍挂接者拒绝；与挂接通过对间隔执行锁行 SELECT FOR UPDATE 互斥。
     */
    public void assertIntervalGroundsRemoved(String intervalId, long ticketId, int stepSeq) {
        lockIntervalRow(intervalId);
        List<String> applied = jdbc.query(
                "SELECT wire_no FROM ground_wire WHERE interval_id = ? AND status = ? ORDER BY wire_no",
                (rs, rowNum) -> rs.getString(1), intervalId, ST_APPLIED);
        if (!applied.isEmpty()) {
            String detail = "送电门禁：间隔 " + intervalId + " 临时接地线 " + applied + " 仍挂接，须全部拆除后方可送电";
            interlockService.recordHit(ticketId, stepSeq, TEMP_GROUND_ENERGIZE, detail);
            throw ApiException.unprocessable("ENERGIZE_GROUND_ATTACHED", detail);
        }
    }

    /** 查询接地线列表，可按占用票过滤。 */
    @Transactional(readOnly = true)
    public List<GroundWireView> list(Long ticketId) {
        List<WireRow> rows = ticketId == null
                ? jdbc.query("SELECT * FROM ground_wire ORDER BY wire_no", WIRE_MAPPER)
                : jdbc.query("SELECT * FROM ground_wire WHERE ticket_id = ? ORDER BY wire_no",
                        WIRE_MAPPER, ticketId);
        return rows.stream().map(GroundWireService::toView).toList();
    }

    /** 查询接地线详情：实物、双人确认证据与借用归还历史。 */
    @Transactional(readOnly = true)
    public GroundWireDetailView detail(String wireNo) {
        WireRow wire = mustLoadWire(wireNo);
        List<GroundWireEventView> events = jdbc.query(
                        "SELECT * FROM ground_wire_event WHERE wire_id = ? ORDER BY id",
                        (rs, rowNum) -> new GroundWireEventView(
                                rs.getLong("id"), rs.getLong("wire_id"), rs.getLong("ticket_id"),
                                rs.getInt("step_seq"), rs.getString("action"),
                                rs.getString("operator_id"), rs.getString("guardian_id"),
                                toInstant(rs.getTimestamp("created_at"))),
                        wire.id());
        List<LoanView> loans = jdbc.query(
                        "SELECT * FROM ground_wire_loan WHERE wire_id = ? ORDER BY id",
                        (rs, rowNum) -> new LoanView(
                                rs.getLong("id"), rs.getLong("wire_id"), rs.getLong("ticket_id"),
                                rs.getString("interval_id"),
                                toInstant(rs.getTimestamp("borrowed_at")),
                                toInstant(rs.getTimestamp("returned_at")),
                                rs.getString("keeper_id")),
                        wire.id());
        return new GroundWireDetailView(toView(wire), events, loans);
    }

    /** 统计操作票当前占用（未归还）的接地线条数，供归档门禁使用。 */
    public int countOutstandingForTicket(long ticketId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM ground_wire WHERE ticket_id = ?", Integer.class, ticketId);
        return count == null ? 0 : count;
    }

    private void lockIntervalRow(String intervalId) {
        jdbc.query("SELECT interval_id FROM interval_execution_lock WHERE interval_id = ? FOR UPDATE",
                (rs, rowNum) -> rs.getString(1), intervalId);
    }

    private void assertTicketBorrowable(TicketRow ticket) {
        switch (ticket.status()) {
            case "ARCHIVED" -> throw ApiException.unprocessable("TICKET_STATE", "已归档操作票不可借用接地线");
            case "EXPIRED" -> throw ApiException.unprocessable("TICKET_STATE", "已作废操作票不可借用接地线");
            case "ISSUED", "EXECUTING" -> {
                if (ticket.expiresAt() != null && !Instant.now(clock).isBefore(ticket.expiresAt())) {
                    throw ApiException.unprocessable("TICKET_EXPIRED", "操作票自预发起已满24小时作废，不可借用接地线");
                }
            }
            default -> {
                // DRAFT/REVIEWED/EXCEPTION：未归档且未作废，允许借用
            }
        }
    }

    private void assertDoubleConfirm(String operatorId, String guardianId) {
        if (operatorId.equals(guardianId)) {
            throw ApiException.badRequest("DOUBLE_CONFIRM_REQUIRED", "操作人与监护人必须为两人，单人操作不生效");
        }
    }

    private void assertBoundToTicket(WireRow wire, Long ticketId) {
        if (wire.ticketId() == null || !wire.ticketId().equals(ticketId)) {
            throw ApiException.unprocessable("WIRE_TICKET_MISMATCH",
                    "接地线 " + wire.wireNo() + " 未借给操作票 " + ticketId);
        }
    }

    private void assertStepExists(long ticketId, int stepSeq) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM switching_step WHERE ticket_id = ? AND seq = ?",
                Integer.class, ticketId, stepSeq);
        if (count == null || count == 0) {
            throw ApiException.notFound("STEP_NOT_FOUND", "操作票 " + ticketId + " 步骤不存在: " + stepSeq);
        }
    }

    private void assertVersion(WireRow wire, int expectedVersion) {
        if (wire.version() != expectedVersion) {
            throw ApiException.conflict("VERSION_CONFLICT",
                    "接地线 " + wire.wireNo() + " 版本冲突：期望 " + expectedVersion
                            + "，当前 " + wire.version());
        }
    }

    private void insertEvent(long wireId, long ticketId, int stepSeq, String action,
                             String operatorId, String guardianId, Instant now) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO ground_wire_event (wire_id, ticket_id, step_seq, action, operator_id, guardian_id,"
                            + " created_at) VALUES (?, ?, ?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, wireId);
            ps.setLong(2, ticketId);
            ps.setInt(3, stepSeq);
            ps.setString(4, action);
            ps.setString(5, operatorId);
            ps.setString(6, guardianId);
            ps.setTimestamp(7, Timestamp.from(now));
            return ps;
        }, keyHolder);
    }

    private TicketRow mustLoadTicket(long ticketId) {
        return jdbc.query("SELECT id, interval_id, status, expires_at FROM switching_ticket WHERE id = ?",
                        (rs, rowNum) -> new TicketRow(rs.getLong("id"), rs.getString("interval_id"),
                                rs.getString("status"), toInstant(rs.getTimestamp("expires_at"))),
                        ticketId)
                .stream().findFirst()
                .orElseThrow(() -> ApiException.notFound("TICKET_NOT_FOUND", "操作票不存在: " + ticketId));
    }

    private WireRow mustLoadWire(String wireNo) {
        return jdbc.query("SELECT * FROM ground_wire WHERE wire_no = ?", WIRE_MAPPER, wireNo)
                .stream().findFirst()
                .orElseThrow(() -> ApiException.notFound("WIRE_NOT_FOUND", "接地线不存在: " + wireNo));
    }

    private static GroundWireView toView(WireRow wire) {
        return new GroundWireView(wire.id(), wire.wireNo(), wire.status(), wire.version(),
                wire.ticketId(), wire.intervalId(), wire.createdAt(), wire.updatedAt());
    }

    private static Instant toInstant(Timestamp ts) {
        return ts == null ? null : ts.toInstant();
    }

    /** 接地线行记录。 */
    record WireRow(long id, String wireNo, String status, int version, Long ticketId, String intervalId,
                   Instant createdAt, Instant updatedAt) {
    }

    /** 操作票借用校验所需行记录。 */
    private record TicketRow(long id, String intervalId, String status, Instant expiresAt) {
    }
}
