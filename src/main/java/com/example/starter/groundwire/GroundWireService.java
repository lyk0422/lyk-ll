package com.example.starter.groundwire;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.starter.error.ApiException;
import com.example.starter.groundwire.GroundWireDtos.ApplyRequest;
import com.example.starter.groundwire.GroundWireDtos.BorrowRecordView;
import com.example.starter.groundwire.GroundWireDtos.BorrowRequest;
import com.example.starter.groundwire.GroundWireDtos.BorrowView;
import com.example.starter.groundwire.GroundWireDtos.OperationView;
import com.example.starter.groundwire.GroundWireDtos.RemoveRequest;
import com.example.starter.groundwire.GroundWireDtos.ReturnRequest;
import com.example.starter.groundwire.GroundWireDtos.ReturnView;
import com.example.starter.groundwire.GroundWireDtos.WireVersionItem;
import com.example.starter.groundwire.GroundWireDtos.WireView;
import com.example.starter.interlock.InterlockResult;
import com.example.starter.interlock.InterlockService;

/**
 * 接地线实物联锁服务：借用→挂接→拆除→归还的实物状态机与不可覆盖操作证据。
 *
 * <p>状态机：AVAILABLE → BORROWED → APPLIED → REMOVED_PENDING_RETURN → AVAILABLE。
 * 借用绑定未归档且未作废的操作票与间隔，一次可借多条，任一不可用整批拒绝；
 * 同一实物同一时刻最多被一张票占用（行锁＋条件更新防并发双占）。
 * 票作废或异常不改变实物状态，仍须合法拆除与归还；归还只核销已拆除实物。</p>
 */
@Service
public class GroundWireService {

    /** 实物状态：可用（在库）。 */
    public static final String AVAILABLE = "AVAILABLE";
    /** 实物状态：已借出未挂接。 */
    public static final String BORROWED = "BORROWED";
    /** 实物状态：已挂接在间隔上。 */
    public static final String APPLIED = "APPLIED";
    /** 实物状态：已拆除待归还。 */
    public static final String REMOVED_PENDING_RETURN = "REMOVED_PENDING_RETURN";

    /** 借用登记状态：借出未核销。 */
    public static final String BORROW_RECORD_OPEN = "BORROWED";
    /** 借用登记状态：已归还核销。 */
    public static final String BORROW_RECORD_RETURNED = "RETURNED";

    /** 挂接操作证据动作。 */
    public static final String ACTION_APPLY = "APPLY";
    /** 拆除操作证据动作。 */
    public static final String ACTION_REMOVE = "REMOVE";

    private static final RowMapper<WireRow> WIRE_MAPPER = (rs, rowNum) -> new WireRow(
            rs.getLong("id"),
            rs.getString("wire_no"),
            rs.getString("status"),
            rs.getString("interval_id"),
            rs.getObject("current_ticket_id") == null ? null : rs.getLong("current_ticket_id"),
            rs.getInt("version"),
            rs.getTimestamp("created_at").toInstant(),
            rs.getTimestamp("updated_at").toInstant());

    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final InterlockService interlockService;

    public GroundWireService(JdbcTemplate jdbc, Clock businessClock, InterlockService interlockService) {
        this.jdbc = jdbc;
        this.clock = businessClock;
        this.interlockService = interlockService;
    }

    /**
     * 借用：绑定未归档且未作废的操作票与间隔。
     * 一次可借多条，任一实物不存在、版本不符或不可用则整批拒绝；并发借用同一实物唯一成功。
     */
    @Transactional
    public BorrowView borrow(long ticketId, BorrowRequest req) {
        TicketRef ticket = mustLoadTicket(ticketId);
        if ("ARCHIVED".equals(ticket.status()) || "EXPIRED".equals(ticket.status())
                || "EXCEPTION".equals(ticket.status())) {
            throw ApiException.unprocessable("TICKET_NOT_BORROWABLE",
                    "已归档、已作废或异常中止的操作票不可借用接地线，当前状态: " + ticket.status());
        }
        if (!ticket.intervalId().equals(req.intervalId())) {
            throw ApiException.unprocessable("INTERVAL_MISMATCH",
                    "借用间隔与操作票间隔不一致: 请求 " + req.intervalId() + "，票 " + ticket.intervalId());
        }
        List<WireVersionItem> orderedItems = sortedItems(req.items());
        List<String> wireNos = orderedItems.stream().map(WireVersionItem::wireNo).toList();
        Instant now = req.occurredAt();
        // 锁定本批次全部实物行后再统一判定，并发借用在数据库行锁上串行化，杜绝双占；
        // 统一按编号升序加锁，避免多批次交叉加锁死锁
        List<WireRow> wires = lockWires(wireNos);
        for (int i = 0; i < wires.size(); i++) {
            WireRow wire = wires.get(i);
            // 先判可用性：并发竞争失败方拿到行锁后看到实物已借出，得到“被占用/不可用”而非版本冲突；
            // 仅当实物仍在库可用时，版本不符才按乐观版本冲突处理
            if (!AVAILABLE.equals(wire.status())) {
                throw ApiException.unprocessable("WIRE_NOT_AVAILABLE",
                        "接地线 " + wire.wireNo() + " 当前状态 " + wire.status() + " 不可借用，整批拒绝");
            }
            if (wire.version() != orderedItems.get(i).expectedVersion()) {
                throw ApiException.conflict("VERSION_MISMATCH",
                        "接地线 " + wire.wireNo() + " 期望版本 " + orderedItems.get(i).expectedVersion()
                                + "，实际版本 " + wire.version());
            }
        }
        for (WireRow wire : wires) {
            int updated = jdbc.update(
                    "UPDATE ground_wire SET status = 'BORROWED', interval_id = ?, current_ticket_id = ?,"
                            + " version = version + 1, updated_at = ? WHERE id = ? AND status = 'AVAILABLE'"
                            + " AND version = ?",
                    req.intervalId(), ticketId, Timestamp.from(now), wire.id(), wire.version());
            if (updated != 1) {
                // 并发竞争：行锁释放后状态已被竞争票改变，整批回滚
                throw ApiException.unprocessable("WIRE_NOT_AVAILABLE",
                        "接地线 " + wire.wireNo() + " 已被竞争操作票占用，整批拒绝");
            }
            jdbc.update(
                    "INSERT INTO ground_wire_borrow (borrow_request_id, ticket_id, interval_id, wire_no, status,"
                            + " borrowed_at) VALUES (?, ?, ?, ?, 'BORROWED', ?)",
                    req.requestId(), ticketId, req.intervalId(), wire.wireNo(), Timestamp.from(now));
        }
        return new BorrowView(req.requestId(), ticketId, req.intervalId(), loadViews(wireNos));
    }

    /**
     * 挂接：须引用本票步骤，操作人与监护人两名不同人员共同确认，保存不可覆盖证据。
     * 挂接前重新校验间隔带电闭锁；与送电确认在数据库行锁上串行，送电通过后挂接必被拒。
     */
    @Transactional
    public OperationView apply(long ticketId, String wireNo, ApplyRequest req) {
        return changePhysical(ticketId, wireNo, req.requestId(), req.expectedVersion(), req.stepSeq(),
                req.operatorId(), req.guardianId(), req.occurredAt(), ACTION_APPLY);
    }

    /**
     * 拆除：同样双人确认与不可覆盖证据。票作废或异常后仍须合法拆除，不自动恢复可用。
     */
    @Transactional
    public OperationView remove(long ticketId, String wireNo, RemoveRequest req) {
        return changePhysical(ticketId, wireNo, req.requestId(), req.expectedVersion(), req.stepSeq(),
                req.operatorId(), req.guardianId(), req.occurredAt(), ACTION_REMOVE);
    }

    /**
     * 归还：仓管核对编号，只核销已拆除待归还实物，实物回库可用，借用登记核销。
     * 任一实物未拆除、版本不符或不属于本票则整批拒绝。
     */
    @Transactional
    public ReturnView returnWires(long ticketId, ReturnRequest req) {
        mustLoadTicket(ticketId);
        List<WireVersionItem> orderedItems = sortedItems(req.items());
        List<String> wireNos = orderedItems.stream().map(WireVersionItem::wireNo).toList();
        Instant now = req.occurredAt();
        List<WireRow> wires = lockWires(wireNos);
        for (int i = 0; i < wires.size(); i++) {
            WireRow wire = wires.get(i);
            Integer expected = orderedItems.get(i).expectedVersion();
            if (wire.version() != expected) {
                throw ApiException.conflict("VERSION_MISMATCH",
                        "接地线 " + wire.wireNo() + " 期望版本 " + expected + "，实际版本 " + wire.version());
            }
            if (!ticketIdEquals(wire.currentTicketId(), ticketId)) {
                throw ApiException.unprocessable("WIRE_NOT_BORROWED_BY_TICKET",
                        "接地线 " + wire.wireNo() + " 不属于本操作票占用，仓管拒绝核销");
            }
            if (!REMOVED_PENDING_RETURN.equals(wire.status())) {
                throw ApiException.unprocessable("WIRE_NOT_REMOVED",
                        "接地线 " + wire.wireNo() + " 当前状态 " + wire.status() + "，仅已拆除实物可归还核销");
            }
        }
        for (WireRow wire : wires) {
            int updated = jdbc.update(
                    "UPDATE ground_wire SET status = 'AVAILABLE', interval_id = NULL, current_ticket_id = NULL,"
                            + " version = version + 1, updated_at = ? WHERE id = ?"
                            + " AND status = 'REMOVED_PENDING_RETURN' AND version = ?",
                    Timestamp.from(now), wire.id(), wire.version());
            if (updated != 1) {
                throw ApiException.unprocessable("WIRE_NOT_REMOVED",
                        "接地线 " + wire.wireNo() + " 状态已被并发改变，整批拒绝归还");
            }
            int records = jdbc.update(
                    "UPDATE ground_wire_borrow SET status = 'RETURNED', return_request_id = ?,"
                            + " warehouse_keeper_id = ?, returned_at = ? WHERE ticket_id = ? AND wire_no = ?"
                            + " AND status = 'BORROWED'",
                    req.requestId(), req.warehouseKeeperId(), Timestamp.from(now), ticketId, wire.wireNo());
            if (records != 1) {
                throw ApiException.unprocessable("BORROW_RECORD_MISSING",
                        "接地线 " + wire.wireNo() + " 未找到本票未核销借用登记，拒绝核销");
            }
        }
        return new ReturnView(req.requestId(), ticketId, req.warehouseKeeperId(), loadViews(wireNos));
    }

    /** 查询单条接地线实物，不存在 404。 */
    @Transactional(readOnly = true)
    public WireView getWire(String wireNo) {
        return toView(mustLoadWire(wireNo));
    }

    /** 查询接地线实物台账，可按状态过滤。 */
    @Transactional(readOnly = true)
    public List<WireView> listWires(String status) {
        List<WireRow> rows = status == null || status.isBlank()
                ? jdbc.query("SELECT * FROM ground_wire ORDER BY wire_no", WIRE_MAPPER)
                : jdbc.query("SELECT * FROM ground_wire WHERE status = ? ORDER BY wire_no", WIRE_MAPPER, status);
        return rows.stream().map(this::toView).toList();
    }

    /** 查询操作票全部借用/核销登记（含历史，归档后保留）。 */
    @Transactional(readOnly = true)
    public List<BorrowRecordView> listBorrows(long ticketId) {
        return jdbc.query(
                "SELECT * FROM ground_wire_borrow WHERE ticket_id = ? ORDER BY id",
                (rs, n) -> new BorrowRecordView(rs.getLong("id"), rs.getString("borrow_request_id"),
                        rs.getString("return_request_id"), rs.getLong("ticket_id"), rs.getString("interval_id"),
                        rs.getString("wire_no"), rs.getString("status"), rs.getString("warehouse_keeper_id"),
                        rs.getTimestamp("borrowed_at").toInstant(),
                        rs.getTimestamp("returned_at") == null ? null : rs.getTimestamp("returned_at").toInstant()),
                ticketId);
    }

    /** 查询操作票挂接/拆除操作证据（只追加，按时间排列）。 */
    @Transactional(readOnly = true)
    public List<OperationView> listOperations(long ticketId) {
        return jdbc.query(
                "SELECT * FROM ground_wire_operation WHERE ticket_id = ? ORDER BY id",
                (rs, n) -> new OperationView(rs.getLong("id"), rs.getString("request_id"), rs.getLong("ticket_id"),
                        rs.getString("wire_no"), rs.getInt("step_seq"), rs.getString("action"),
                        rs.getString("operator_id"), rs.getString("guardian_id"),
                        rs.getTimestamp("operated_at").toInstant(), rs.getTimestamp("created_at").toInstant()),
                ticketId);
    }

    /**
     * 送电类步确认实物联锁：事务内重新检查该间隔所有借用接地线均已拆除，
     * 锁定该间隔全部实物行使挂接与送电并发串行化；仍有挂接者返回拒绝详情。
     *
     * @return 非空详情表示应拦截（含挂接编号），空表示放行
     */
    public List<String> findAppliedWiresForEnergize(String intervalId) {
        List<WireRow> bound = jdbc.query(
                "SELECT * FROM ground_wire WHERE interval_id = ? FOR UPDATE", WIRE_MAPPER, intervalId);
        return bound.stream().filter(w -> APPLIED.equals(w.status())).map(WireRow::wireNo).toList();
    }

    /** 归档前置条件：本票借用全部归还核销，仍有未归还记录返回编号。 */
    @Transactional(readOnly = true)
    public List<String> openWireNosForTicket(long ticketId) {
        return jdbc.query(
                "SELECT wire_no FROM ground_wire_borrow WHERE ticket_id = ? AND status = 'BORROWED' ORDER BY wire_no",
                (rs, n) -> rs.getString(1), ticketId);
    }

    // ---------- 内部实现 ----------

    private OperationView changePhysical(long ticketId, String wireNo, String requestId, int expectedVersion,
                                         int stepSeq, String operatorId, String guardianId, Instant occurredAt,
                                         String action) {
        if (operatorId.equals(guardianId)) {
            throw ApiException.badRequest("DOUBLE_CONFIRM_REQUIRED",
                    actionText(action) + "须操作人与监护人两名不同人员共同确认");
        }
        TicketRef ticket = mustLoadTicket(ticketId);
        if (ACTION_APPLY.equals(action)
                && !"ISSUED".equals(ticket.status()) && !"EXECUTING".equals(ticket.status())) {
            throw ApiException.unprocessable("TICKET_STATE",
                    "仅预发或执行中操作票可挂接接地线，当前状态: " + ticket.status());
        }
        if (ACTION_REMOVE.equals(action) && !"ISSUED".equals(ticket.status())
                && !"EXECUTING".equals(ticket.status()) && !"EXCEPTION".equals(ticket.status())
                && !"EXPIRED".equals(ticket.status())) {
            throw ApiException.unprocessable("TICKET_STATE",
                    "当前状态不允许拆除接地线: " + ticket.status());
        }
        // 挂接/拆除须引用本票步骤，且步骤操作类型须与实物动作一致；
        // 实物动作即该步骤的双人确认执行：活动票须逐项执行，作废/异常票允许补拆未完成的拆除步
        String expectedOperation = ACTION_APPLY.equals(action) ? "APPLY_GROUND" : "REMOVE_GROUND";
        StepRef step = jdbc.query(
                        "SELECT operation_type, status FROM switching_step WHERE ticket_id = ? AND seq = ?",
                        (rs, n) -> new StepRef(rs.getString(1), rs.getString(2)), ticketId, stepSeq)
                .stream().findFirst().orElse(null);
        if (step == null) {
            throw ApiException.notFound("STEP_NOT_FOUND",
                    "操作票 " + ticketId + " 不存在步序 " + stepSeq + "，" + actionText(action) + "须引用本票步骤");
        }
        if (!expectedOperation.equals(step.operationType())) {
            throw ApiException.unprocessable("STEP_TYPE_MISMATCH",
                    "步序 " + stepSeq + " 操作类型为 " + step.operationType() + "，" + actionText(action)
                            + "接地线须引用 " + expectedOperation + " 步骤");
        }
        if (!"PENDING".equals(step.status())) {
            throw ApiException.unprocessable("STEP_STATE",
                    "步序 " + stepSeq + " 已执行，当前状态: " + step.status());
        }
        boolean activeTicket = "ISSUED".equals(ticket.status()) || "EXECUTING".equals(ticket.status());
        if (activeTicket) {
            Integer nextSeq = jdbc.query(
                            "SELECT MIN(seq) FROM switching_step WHERE ticket_id = ? AND status = 'PENDING'",
                            (rs, n) -> rs.getInt(1), ticketId)
                    .stream().findFirst().orElse(null);
            if (nextSeq == null || nextSeq != stepSeq) {
                throw ApiException.unprocessable("STEP_ORDER",
                        "须逐项执行，当前应执行步序: " + nextSeq + "，" + actionText(action) + "引用步序 " + stepSeq);
            }
        }
        WireRow wire = lockWire(wireNo);
        if (wire.version() != expectedVersion) {
            throw ApiException.conflict("VERSION_MISMATCH",
                    "接地线 " + wireNo + " 期望版本 " + expectedVersion + "，实际版本 " + wire.version());
        }
        if (!ticketIdEquals(wire.currentTicketId(), ticketId)) {
            throw ApiException.unprocessable("WIRE_NOT_BORROWED_BY_TICKET",
                    "接地线 " + wireNo + " 未被本操作票借用占用，禁止" + actionText(action));
        }
        String targetStatus = ACTION_APPLY.equals(action) ? APPLIED : REMOVED_PENDING_RETURN;
        String requiredStatus = ACTION_APPLY.equals(action) ? BORROWED : APPLIED;
        if (!requiredStatus.equals(wire.status())) {
            throw ApiException.unprocessable("WIRE_STATE",
                    "接地线 " + wireNo + " 当前状态 " + wire.status() + "，" + actionText(action)
                            + "要求状态 " + requiredStatus);
        }
        if (ACTION_APPLY.equals(action)) {
            // 挂接前在同一事务内按借用绑定间隔重新核对带电状态，送电先提交后本事务必读到合位并被拦
            InterlockResult live = interlockService.evaluateInInterval("APPLY_GROUND", ticket.intervalId());
            if (live.blocked()) {
                interlockService.recordHit(ticketId, stepSeq, live.ruleCode(), live.detail());
                throw ApiException.unprocessable("INTERLOCK_BLOCKED",
                        live.detail() + " [" + live.ruleCode() + "]");
            }
        }
        // 证据先行且只追加；条件更新兜底并发版本竞争
        jdbc.update(
                "INSERT INTO ground_wire_operation (request_id, ticket_id, wire_no, step_seq, action, operator_id,"
                        + " guardian_id, operated_at, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                requestId, ticketId, wireNo, stepSeq, action, operatorId, guardianId,
                Timestamp.from(occurredAt), Timestamp.from(Instant.now(clock)));
        int updated = jdbc.update(
                "UPDATE ground_wire SET status = ?, version = version + 1, updated_at = ? WHERE id = ?"
                        + " AND status = ? AND version = ?",
                targetStatus, Timestamp.from(occurredAt), wire.id(), requiredStatus, expectedVersion);
        if (updated != 1) {
            throw ApiException.conflict("VERSION_MISMATCH",
                    "接地线 " + wireNo + " 状态已被并发改变，" + actionText(action) + "失败");
        }
        // 实物动作即步骤执行：条件更新占用步骤，与普通步确认并发时唯一成功
        int stepUpdated = jdbc.update(
                "UPDATE switching_step SET status = 'DONE', operator_id = ?, guardian_id = ?, executed_at = ?"
                        + " WHERE ticket_id = ? AND seq = ? AND status = 'PENDING'",
                operatorId, guardianId, Timestamp.from(occurredAt), ticketId, stepSeq);
        if (stepUpdated != 1) {
            throw ApiException.unprocessable("STEP_STATE",
                    "步序 " + stepSeq + " 已被并发确认执行，" + actionText(action) + "整事务回滚");
        }
        if (activeTicket) {
            jdbc.update(
                    "UPDATE switching_ticket SET status = 'EXECUTING', updated_at = ? WHERE id = ?"
                            + " AND status IN ('ISSUED','EXECUTING')",
                    Timestamp.from(occurredAt), ticketId);
        }
        OperationView view = jdbc.queryForObject(
                "SELECT * FROM ground_wire_operation WHERE request_id = ? AND ticket_id = ? AND wire_no = ?"
                        + " AND action = ?",
                (rs, n) -> new OperationView(rs.getLong("id"), rs.getString("request_id"), rs.getLong("ticket_id"),
                        rs.getString("wire_no"), rs.getInt("step_seq"), rs.getString("action"),
                        rs.getString("operator_id"), rs.getString("guardian_id"),
                        rs.getTimestamp("operated_at").toInstant(), rs.getTimestamp("created_at").toInstant()),
                requestId, ticketId, wireNo, action);
        return view;
    }

    /**
     * 校验明细非空（Bean Validation 已兜底）、编号不重复，并按编号升序排列，
     * 使多批次并发借用时所有事务按同一顺序对实物行加锁，避免交叉加锁死锁。
     */
    private List<WireVersionItem> sortedItems(List<WireVersionItem> items) {
        Set<String> seen = new LinkedHashSet<>();
        for (WireVersionItem item : items) {
            if (!seen.add(item.wireNo())) {
                throw ApiException.badRequest("DUPLICATE_WIRE_ITEM",
                        "同一接地线在批次中重复出现: " + item.wireNo());
            }
        }
        return items.stream().sorted(java.util.Comparator.comparing(WireVersionItem::wireNo)).toList();
    }

    /** 按编号顺序锁定实物行（FOR UPDATE），编号不存在抛 404。 */
    private List<WireRow> lockWires(List<String> wireNos) {
        List<WireRow> wires = new ArrayList<>();
        for (String wireNo : wireNos) {
            wires.add(lockWire(wireNo));
        }
        return wires;
    }

    private WireRow lockWire(String wireNo) {
        return jdbc.query("SELECT * FROM ground_wire WHERE wire_no = ? FOR UPDATE", WIRE_MAPPER, wireNo)
                .stream().findFirst()
                .orElseThrow(() -> ApiException.notFound("WIRE_NOT_FOUND", "接地线不存在: " + wireNo));
    }

    private WireRow mustLoadWire(String wireNo) {
        return jdbc.query("SELECT * FROM ground_wire WHERE wire_no = ?", WIRE_MAPPER, wireNo)
                .stream().findFirst()
                .orElseThrow(() -> ApiException.notFound("WIRE_NOT_FOUND", "接地线不存在: " + wireNo));
    }

    private List<WireView> loadViews(List<String> wireNos) {
        return wireNos.stream().map(this::mustLoadWire).map(this::toView).toList();
    }

    private WireView toView(WireRow wire) {
        return new WireView(wire.id(), wire.wireNo(), wire.status(), wire.intervalId(), wire.currentTicketId(),
                wire.version(), wire.createdAt(), wire.updatedAt());
    }

    private TicketRef mustLoadTicket(long ticketId) {
        return jdbc.query("SELECT interval_id, status FROM switching_ticket WHERE id = ?",
                        (rs, n) -> new TicketRef(rs.getString(1), rs.getString(2)), ticketId)
                .stream().findFirst()
                .orElseThrow(() -> ApiException.notFound("TICKET_NOT_FOUND", "操作票不存在: " + ticketId));
    }

    private static boolean ticketIdEquals(Long bound, long ticketId) {
        return bound != null && bound == ticketId;
    }

    private static String actionText(String action) {
        return ACTION_APPLY.equals(action) ? "挂接" : "拆除";
    }

    /** 接地线实物行记录。 */
    record WireRow(long id, String wireNo, String status, String intervalId, Long currentTicketId,
                   int version, Instant createdAt, Instant updatedAt) {
    }

    /** 操作票引用：间隔与状态。 */
    record TicketRef(String intervalId, String status) {
    }

    /** 步骤引用：操作类型与状态。 */
    record StepRef(String operationType, String status) {
    }
}
