package com.example.starter.ground;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import com.example.starter.error.ApiException;
import com.example.starter.ground.GroundWireDtos.AttachRequest;
import com.example.starter.ground.GroundWireDtos.BatchView;
import com.example.starter.ground.GroundWireDtos.BorrowRequest;
import com.example.starter.ground.GroundWireDtos.DetachRequest;
import com.example.starter.ground.GroundWireDtos.ReturnRequest;
import com.example.starter.ground.GroundWireDtos.TicketGroundView;
import com.example.starter.ground.GroundWireDtos.WireDetailView;
import com.example.starter.ground.GroundWireDtos.WireEventView;
import com.example.starter.ground.GroundWireDtos.WireItem;
import com.example.starter.ground.GroundWireDtos.WireView;
import com.example.starter.interlock.InterlockService;

/**
 * 临时接地线借用核销服务：借用、挂接、拆除、归还核销与查询。
 * 状态机：AVAILABLE可用 → BORROWED借出 → ATTACHED已挂接 → DETACHED已拆除待归还 → AVAILABLE；
 * 乐观版本从1开始，每次成功变更加1；挂接/拆除双人确认并保存不可覆盖证据；
 * 票作废或异常不自动释放实物，仍须合法拆除与归还。
 */
@Service
public class GroundWireService {

    private static final Set<String> ACTIVE_TICKET_STATUSES = Set.of("ISSUED", "EXECUTING");

    private static final RowMapper<WireRow> WIRE_MAPPER = (rs, rowNum) -> new WireRow(
            rs.getLong("id"),
            rs.getString("wire_no"),
            rs.getString("status"),
            rs.getObject("holder_ticket_id") == null ? null : rs.getLong("holder_ticket_id"),
            rs.getString("interval_id"),
            rs.getInt("version"),
            toInstant(rs.getTimestamp("updated_at")));

    private static final RowMapper<TicketRow> TICKET_MAPPER = (rs, rowNum) -> new TicketRow(
            rs.getLong("id"),
            rs.getString("interval_id"),
            rs.getString("status"),
            toInstant(rs.getTimestamp("expires_at")));

    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final InterlockService interlockService;
    private final TransactionTemplate requiresNewTx;

    public GroundWireService(JdbcTemplate jdbc, Clock businessClock, InterlockService interlockService,
                             PlatformTransactionManager txManager) {
        this.jdbc = jdbc;
        this.clock = businessClock;
        this.interlockService = interlockService;
        this.requiresNewTx = new TransactionTemplate(txManager);
        this.requiresNewTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /**
     * 借用：绑定未归档且未作废的操作票及间隔，一次可借多条；
     * 任一编号不存在、不可用或版本不符即整批拒绝，事务回滚不部分借出。
     */
    @Transactional
    public BatchView borrow(BorrowRequest req) {
        TicketRow ticket = loadTicket(req.ticketId());
        ticket = refreshExpiry(ticket);
        if ("ARCHIVED".equals(ticket.status()) || "EXPIRED".equals(ticket.status())) {
            throw ApiException.unprocessable("TICKET_STATE",
                    "借用须绑定未归档且未作废的操作票，当前状态: " + ticket.status());
        }
        rejectDuplicateWireNos(req.items());
        // 预加载全部编号，任一不存在即 404，整批拒绝
        List<WireRow> wires = req.items().stream().map(item -> loadWire(item.wireNo())).toList();
        Instant now = Instant.now(clock);
        for (int i = 0; i < wires.size(); i++) {
            WireItem item = req.items().get(i);
            WireRow wire = wires.get(i);
            int updated = jdbc.update(
                    "UPDATE ground_wire SET status = 'BORROWED', holder_ticket_id = ?, interval_id = ?,"
                            + " version = version + 1, updated_at = ?"
                            + " WHERE wire_no = ? AND status = 'AVAILABLE' AND version = ?",
                    ticket.id(), ticket.intervalId(), Timestamp.from(now),
                    item.wireNo(), item.expectedVersion());
            if (updated == 0) {
                throwUpdateFailure(item.wireNo(), item.expectedVersion(), ticket.id(), "AVAILABLE", "借用");
            }
            insertEvent(wire.id(), wire.wireNo(), ticket.id(), "BORROW",
                    null, null, null, null, req.occurredAt(), now);
        }
        return new BatchView(ticket.id(), wiresOf(req.items()));
    }

    /**
     * 挂接：引用本票步骤，操作人与监护人两名不同人员共同确认；
     * 持票行锁与送电确认互斥，间隔带电时拒绝挂接；保存不可覆盖证据。
     */
    @Transactional
    public WireView attach(AttachRequest req) {
        if (req.operatorId().equals(req.guardianId())) {
            throw ApiException.badRequest("DOUBLE_CONFIRM_REQUIRED", "挂接须操作人与监护人两名不同人员共同确认");
        }
        TicketRow ticket = loadTicket(req.ticketId());
        ticket = refreshExpiry(ticket);
        ticket = lockTicket(ticket.id());
        if (!ACTIVE_TICKET_STATUSES.contains(ticket.status())) {
            throw ApiException.unprocessable("TICKET_STATE",
                    "挂接要求操作票处于预发或执行中，当前状态: " + ticket.status());
        }
        Integer stepCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM switching_step WHERE ticket_id = ? AND seq = ?",
                Integer.class, req.ticketId(), req.stepSeq());
        if (stepCount == null || stepCount == 0) {
            throw ApiException.notFound("STEP_NOT_FOUND",
                    "步骤不存在或不属于本票: ticket=" + req.ticketId() + ", seq=" + req.stepSeq());
        }
        // 带电挂接地线检查：持票行锁期间与送电类步确认互斥，间隔开关合位即带电
        List<String> closedBreakers = jdbc.query(
                "SELECT device_id FROM device_state WHERE interval_id = ? AND device_type = 'BREAKER'"
                        + " AND state = 'CLOSED'",
                (rs, rowNum) -> rs.getString(1), ticket.intervalId());
        if (!closedBreakers.isEmpty()) {
            throw ApiException.unprocessable("GROUND_LIVE_INTERVAL",
                    "带电挂接地线：间隔 " + ticket.intervalId() + " 开关 " + closedBreakers + " 仍在合位");
        }
        WireRow wire = loadWire(req.wireNo());
        Instant now = Instant.now(clock);
        int updated = jdbc.update(
                "UPDATE ground_wire SET status = 'ATTACHED', version = version + 1, updated_at = ?"
                        + " WHERE wire_no = ? AND status = 'BORROWED' AND holder_ticket_id = ? AND version = ?",
                Timestamp.from(now), req.wireNo(), req.ticketId(), req.expectedVersion());
        if (updated == 0) {
            throwUpdateFailure(req.wireNo(), req.expectedVersion(), req.ticketId(), "BORROWED", "挂接");
        }
        insertEvent(wire.id(), wire.wireNo(), req.ticketId(), "ATTACH",
                req.stepSeq(), req.operatorId(), req.guardianId(), null, req.occurredAt(), now);
        return wireView(req.wireNo());
    }

    /**
     * 拆除：操作人与监护人两名不同人员共同确认，保存不可覆盖证据；
     * 票已作废或异常时仍允许合法拆除（实物不自动变回可用）。
     */
    @Transactional
    public WireView detach(DetachRequest req) {
        if (req.operatorId().equals(req.guardianId())) {
            throw ApiException.badRequest("DOUBLE_CONFIRM_REQUIRED", "拆除须操作人与监护人两名不同人员共同确认");
        }
        TicketRow ticket = loadTicket(req.ticketId());
        lockTicket(ticket.id());
        WireRow wire = loadWire(req.wireNo());
        Instant now = Instant.now(clock);
        int updated = jdbc.update(
                "UPDATE ground_wire SET status = 'DETACHED', version = version + 1, updated_at = ?"
                        + " WHERE wire_no = ? AND status = 'ATTACHED' AND holder_ticket_id = ? AND version = ?",
                Timestamp.from(now), req.wireNo(), req.ticketId(), req.expectedVersion());
        if (updated == 0) {
            throwUpdateFailure(req.wireNo(), req.expectedVersion(), req.ticketId(), "ATTACHED", "拆除");
        }
        insertEvent(wire.id(), wire.wireNo(), req.ticketId(), "DETACH",
                null, req.operatorId(), req.guardianId(), null, req.occurredAt(), now);
        return wireView(req.wireNo());
    }

    /**
     * 归还核销：仓管核对编号，只核销已拆除（DETACHED）实物；
     * 任一编号未拆除或不属于本票即整批拒绝。
     */
    @Transactional
    public BatchView returnWires(ReturnRequest req) {
        TicketRow ticket = loadTicket(req.ticketId());
        rejectDuplicateWireNos(req.items());
        List<WireRow> wires = req.items().stream().map(item -> loadWire(item.wireNo())).toList();
        Instant now = Instant.now(clock);
        for (int i = 0; i < wires.size(); i++) {
            WireItem item = req.items().get(i);
            WireRow wire = wires.get(i);
            int updated = jdbc.update(
                    "UPDATE ground_wire SET status = 'AVAILABLE', holder_ticket_id = NULL, interval_id = NULL,"
                            + " version = version + 1, updated_at = ?"
                            + " WHERE wire_no = ? AND status = 'DETACHED' AND holder_ticket_id = ? AND version = ?",
                    Timestamp.from(now), item.wireNo(), ticket.id(), item.expectedVersion());
            if (updated == 0) {
                throwUpdateFailure(item.wireNo(), item.expectedVersion(), ticket.id(), "DETACHED", "归还核销");
            }
            insertEvent(wire.id(), wire.wireNo(), ticket.id(), "RETURN",
                    null, null, null, req.keeperId(), req.occurredAt(), now);
        }
        return new BatchView(ticket.id(), wiresOf(req.items()));
    }

    /**
     * 送电门禁：在调用方事务内重新检查该间隔所有临时接地线均已拆除，
     * 任何仍挂接者拒绝并记录闭锁命中，不信任拟票时快照。
     */
    public void assertNoAttachedForInterval(String intervalId, long ticketId, int stepSeq) {
        List<String> attached = jdbc.query(
                "SELECT wire_no FROM ground_wire WHERE interval_id = ? AND status = 'ATTACHED' ORDER BY wire_no",
                (rs, rowNum) -> rs.getString(1), intervalId);
        if (!attached.isEmpty()) {
            String detail = "带地线送电：间隔 " + intervalId + " 临时接地线 " + attached + " 仍挂接，须全部拆除";
            interlockService.recordHit(ticketId, stepSeq, InterlockService.GROUNDED_ENERGIZE, detail);
            throw ApiException.unprocessable("GROUND_WIRE_ATTACHED", detail);
        }
    }

    /** 归档门禁：本票借用的接地线须全部归还（无持有中实物）方可归档。 */
    public void assertAllReturned(long ticketId) {
        List<String> held = jdbc.query(
                "SELECT wire_no FROM ground_wire WHERE holder_ticket_id = ? ORDER BY wire_no",
                (rs, rowNum) -> rs.getString(1), ticketId);
        if (!held.isEmpty()) {
            throw ApiException.unprocessable("GROUND_WIRE_NOT_RETURNED",
                    "操作票 " + ticketId + " 借用接地线 " + held + " 未全部归还核销，不得归档");
        }
    }

    /** 查询全部接地线台账。 */
    @Transactional(readOnly = true)
    public List<WireView> listWires() {
        return jdbc.query("SELECT * FROM ground_wire ORDER BY wire_no", WIRE_MAPPER)
                .stream().map(GroundWireService::toView).toList();
    }

    /** 查询接地线详情与全部操作证据。 */
    @Transactional(readOnly = true)
    public WireDetailView wireDetail(String wireNo) {
        WireRow wire = loadWire(wireNo);
        return new WireDetailView(toView(wire), eventsOf("wire_id = ?", wire.id()));
    }

    /** 查询操作票维度接地线：当前持有与历史证据（归档后仍保留）。 */
    @Transactional(readOnly = true)
    public TicketGroundView ticketGround(long ticketId) {
        loadTicket(ticketId);
        List<WireView> held = jdbc.query(
                        "SELECT * FROM ground_wire WHERE holder_ticket_id = ? ORDER BY wire_no",
                        WIRE_MAPPER, ticketId)
                .stream().map(GroundWireService::toView).toList();
        return new TicketGroundView(ticketId, held, eventsOf("ticket_id = ?", ticketId));
    }

    /**
     * 有效期检查：预发或执行中的票恰满 24 小时（UTC）即作废并释放间隔锁，
     * 作废转换独立事务提交；不外借、不挂接，但不影响既有实物的合法拆除与归还。
     */
    private TicketRow refreshExpiry(TicketRow ticket) {
        boolean expirable = ACTIVE_TICKET_STATUSES.contains(ticket.status());
        if (expirable && ticket.expiresAt() != null && !Instant.now(clock).isBefore(ticket.expiresAt())) {
            requiresNewTx.executeWithoutResult(s -> {
                jdbc.update("UPDATE switching_ticket SET status = 'EXPIRED', updated_at = ? WHERE id = ?",
                        Timestamp.from(Instant.now(clock)), ticket.id());
                jdbc.update("DELETE FROM interval_execution_lock WHERE interval_id = ? AND ticket_id = ?",
                        ticket.intervalId(), ticket.id());
            });
            return loadTicket(ticket.id());
        }
        return ticket;
    }

    /**
     * 状态变更失败分流：版本不符 409，不属本票或状态不符 422，编号不存在 404。
     */
    private void throwUpdateFailure(String wireNo, int expectedVersion, long ticketId,
                                    String requiredStatus, String action) {
        WireRow current = loadWire(wireNo);
        if (current.version() != expectedVersion) {
            throw ApiException.conflict("VERSION_CONFLICT",
                    action + "版本冲突：接地线 " + wireNo + " 当前版本 " + current.version()
                            + "，期望版本 " + expectedVersion);
        }
        if (current.holderTicketId() == null || current.holderTicketId() != ticketId) {
            throw ApiException.unprocessable("WIRE_NOT_HELD",
                    action + "拒绝：接地线 " + wireNo + " 未被操作票 " + ticketId + " 占用");
        }
        throw ApiException.unprocessable("WIRE_STATE",
                action + "拒绝：接地线 " + wireNo + " 须处于 " + requiredStatus
                        + "，当前状态 " + current.status());
    }

    private void rejectDuplicateWireNos(List<WireItem> items) {
        Set<String> seen = new HashSet<>();
        for (WireItem item : items) {
            if (!seen.add(item.wireNo())) {
                throw ApiException.badRequest("DUPLICATE_WIRE", "同批接地线编号重复: " + item.wireNo());
            }
        }
    }

    private void insertEvent(long wireId, String wireNo, long ticketId, String action, Integer stepSeq,
                             String operatorId, String guardianId, String keeperId,
                             Instant occurredAt, Instant now) {
        jdbc.update(
                "INSERT INTO ground_wire_event (wire_id, wire_no, ticket_id, action, step_seq,"
                        + " operator_id, guardian_id, keeper_id, occurred_at, created_at)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                wireId, wireNo, ticketId, action, stepSeq, operatorId, guardianId, keeperId,
                Timestamp.from(occurredAt), Timestamp.from(now));
    }

    private List<WireEventView> eventsOf(String whereClause, Object arg) {
        return jdbc.query(
                "SELECT * FROM ground_wire_event WHERE " + whereClause + " ORDER BY id",
                (rs, rowNum) -> new WireEventView(
                        rs.getLong("id"),
                        rs.getString("wire_no"),
                        rs.getLong("ticket_id"),
                        rs.getString("action"),
                        rs.getObject("step_seq") == null ? null : rs.getInt("step_seq"),
                        rs.getString("operator_id"),
                        rs.getString("guardian_id"),
                        rs.getString("keeper_id"),
                        toInstant(rs.getTimestamp("occurred_at"))),
                arg);
    }

    private List<WireView> wiresOf(List<WireItem> items) {
        return items.stream().map(item -> wireView(item.wireNo())).toList();
    }

    private WireView wireView(String wireNo) {
        return toView(loadWire(wireNo));
    }

    private static WireView toView(WireRow wire) {
        return new WireView(wire.wireNo(), wire.status(), wire.holderTicketId(), wire.intervalId(),
                wire.version(), wire.updatedAt());
    }

    private WireRow loadWire(String wireNo) {
        return jdbc.query("SELECT * FROM ground_wire WHERE wire_no = ?", WIRE_MAPPER, wireNo)
                .stream().findFirst()
                .orElseThrow(() -> ApiException.notFound("WIRE_NOT_FOUND", "接地线不存在: " + wireNo));
    }

    private TicketRow loadTicket(long ticketId) {
        return jdbc.query(
                        "SELECT id, interval_id, status, expires_at FROM switching_ticket WHERE id = ?",
                        TICKET_MAPPER, ticketId)
                .stream().findFirst()
                .orElseThrow(() -> ApiException.notFound("TICKET_NOT_FOUND", "操作票不存在: " + ticketId));
    }

    private TicketRow lockTicket(long ticketId) {
        return jdbc.query(
                        "SELECT id, interval_id, status, expires_at FROM switching_ticket WHERE id = ? FOR UPDATE",
                        TICKET_MAPPER, ticketId)
                .stream().findFirst()
                .orElseThrow(() -> ApiException.notFound("TICKET_NOT_FOUND", "操作票不存在: " + ticketId));
    }

    private static Instant toInstant(Timestamp ts) {
        return ts == null ? null : ts.toInstant();
    }

    /** 接地线行记录。 */
    record WireRow(long id, String wireNo, String status, Long holderTicketId, String intervalId,
                   int version, Instant updatedAt) {
    }

    /** 操作票行记录（接地线流程所需字段）。 */
    record TicketRow(long id, String intervalId, String status, Instant expiresAt) {
    }
}
