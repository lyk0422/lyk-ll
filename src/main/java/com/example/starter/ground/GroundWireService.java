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
import org.springframework.transaction.annotation.Transactional;

import com.example.starter.error.ApiException;
import com.example.starter.ground.GroundWireDtos.AttachWireRequest;
import com.example.starter.ground.GroundWireDtos.BorrowWiresRequest;
import com.example.starter.ground.GroundWireDtos.DetachWireRequest;
import com.example.starter.ground.GroundWireDtos.EvidenceView;
import com.example.starter.ground.GroundWireDtos.LoanView;
import com.example.starter.ground.GroundWireDtos.ReturnWiresRequest;
import com.example.starter.ground.GroundWireDtos.WireBatchResult;
import com.example.starter.ground.GroundWireDtos.WireDetailView;
import com.example.starter.ground.GroundWireDtos.WireStepResult;
import com.example.starter.ground.GroundWireDtos.WireView;

/**
 * 实物接地线服务：批量借用、挂接/拆除双人确认留痕、归还核销与查询。
 * 实物台账 ground_wire 与设备遥信 device_state 相互独立，实物占用关系以本台账为准。
 * 所有占用变更均以“当前状态＋占有票＋版本”为条件做条件更新，竞争失败整事务回滚，杜绝双占。
 */
@Service
public class GroundWireService {

    /** 挂接动作证据。 */
    public static final String ACTION_APPLIED = "APPLIED";
    /** 拆除动作证据。 */
    public static final String ACTION_REMOVED = "REMOVED";

    private static final RowMapper<WireRow> WIRE_MAPPER = (rs, n) -> new WireRow(
            rs.getString("code"),
            rs.getString("status"),
            rs.getString("interval_id"),
            (Long) rs.getObject("occupied_by_ticket"),
            rs.getInt("version"),
            rs.getTimestamp("updated_at").toInstant());

    private static final RowMapper<LoanRow> LOAN_MAPPER = (rs, n) -> new LoanRow(
            rs.getLong("id"),
            rs.getString("request_id"),
            rs.getLong("ticket_id"),
            rs.getString("interval_id"),
            rs.getString("wire_code"),
            rs.getString("status"),
            rs.getTimestamp("borrowed_at").toInstant(),
            rs.getTimestamp("returned_at") == null ? null : rs.getTimestamp("returned_at").toInstant(),
            rs.getString("return_request_id"),
            rs.getString("keeper_id"));

    private static final RowMapper<EvidenceRow> EVIDENCE_MAPPER = (rs, n) -> new EvidenceRow(
            rs.getLong("id"),
            rs.getString("wire_code"),
            rs.getLong("ticket_id"),
            rs.getInt("step_seq"),
            rs.getString("action"),
            rs.getString("operator_id"),
            rs.getString("guardian_id"),
            rs.getTimestamp("occurred_at").toInstant(),
            rs.getString("request_id"),
            rs.getTimestamp("created_at").toInstant());

    private final JdbcTemplate jdbc;
    private final Clock clock;

    public GroundWireService(JdbcTemplate jdbc, Clock businessClock) {
        this.jdbc = jdbc;
        this.clock = businessClock;
    }

    /**
     * 批量借用：绑定未归档且未作废的操作票及间隔，一次可借多条，任一不可用整批拒绝。
     * 并发竞争同一实物时条件更新失败，整批回滚不双占。
     */
    @Transactional
    public WireBatchResult borrow(long ticketId, BorrowWiresRequest req) {
        TicketRef ticket = mustLoadTicket(ticketId);
        if ("ARCHIVED".equals(ticket.status())) {
            throw ApiException.unprocessable("TICKET_ARCHIVED", "操作票已归档，不可再借用接地线");
        }
        if ("EXPIRED".equals(ticket.status())) {
            throw ApiException.unprocessable("TICKET_VOID", "操作票已作废，不可再借用接地线");
        }
        List<String> codes = normalizedCodes(req.wireCodes());
        // 先逐条校验：任一不存在、不可用或版本不符，整批拒绝（不产生部分占用）
        for (String code : codes) {
            WireRow wire = mustLoadWire(code);
            if (!"AVAILABLE".equals(wire.status()) || wire.occupiedByTicket() != null) {
                throw ApiException.unprocessable("WIRE_NOT_AVAILABLE",
                        "接地线 " + code + " 当前不可借用，状态: " + wire.status());
            }
            if (wire.version() != req.expectedVersion()) {
                throw ApiException.unprocessable("VERSION_MISMATCH",
                        "接地线 " + code + " 期望版本 " + req.expectedVersion() + " 与当前版本 " + wire.version() + " 不符");
            }
        }
        Instant now = Instant.now(clock);
        for (String code : codes) {
            int updated = jdbc.update(
                    "UPDATE ground_wire SET status = 'BORROWED', interval_id = ?, occupied_by_ticket = ?,"
                            + " version = version + 1, updated_at = ?"
                            + " WHERE code = ? AND status = 'AVAILABLE' AND occupied_by_ticket IS NULL AND version = ?",
                    ticket.intervalId(), ticketId, Timestamp.from(now), code, req.expectedVersion());
            if (updated == 0) {
                // 并发竞争：实物刚被其他票占用，整批拒绝回滚
                throw ApiException.unprocessable("WIRE_NOT_AVAILABLE",
                        "接地线 " + code + " 已被其他操作票占用，整批借用拒绝");
            }
            jdbc.update(
                    "INSERT INTO ground_wire_loan (request_id, ticket_id, interval_id, wire_code, status,"
                            + " borrowed_at, created_at) VALUES (?, ?, ?, ?, 'BORROWED', ?, ?)",
                    req.requestId(), ticketId, ticket.intervalId(), code,
                    Timestamp.from(req.occurredAt()), Timestamp.from(now));
        }
        return new WireBatchResult("BORROWED", (int) (req.expectedVersion() + 1), codes, req.occurredAt());
    }

    /**
     * 批量归还：仓管核对编号，仅核销本票借出（含继承链接管）且已拆除待归还的实物；
     * 已挂接或非本票责任链的实物一律拒绝，整批回滚。
     */
    @Transactional
    public WireBatchResult returnWires(long ticketId, ReturnWiresRequest req) {
        mustLoadTicket(ticketId);
        List<String> codes = normalizedCodes(req.wireCodes());
        for (String code : codes) {
            WireRow wire = mustLoadWire(code);
            if (wire.version() != req.expectedVersion()) {
                throw ApiException.unprocessable("VERSION_MISMATCH",
                        "接地线 " + code + " 期望版本 " + req.expectedVersion() + " 与当前版本 " + wire.version() + " 不符");
            }
            Long loanId = findResponsibleLoan(ticketId, code);
            if (loanId == null) {
                throw ApiException.unprocessable("WIRE_NOT_BORROWED_BY_TICKET",
                        "接地线 " + code + " 非本票借用且不在本票继承链，仓管核对拒绝核销");
            }
            if (wire.occupiedByTicket() == null || wire.occupiedByTicket() != ticketId
                    || !"REMOVED_PENDING_RETURN".equals(wire.status())) {
                throw ApiException.unprocessable("WIRE_NOT_DETACHED",
                        "接地线 " + code + " 尚未拆除，仅已拆除实物可归还核销，当前状态: " + wire.status());
            }
        }
        Instant now = Instant.now(clock);
        for (String code : codes) {
            long loanId = findResponsibleLoan(ticketId, code);
            int updated = jdbc.update(
                    "UPDATE ground_wire SET status = 'AVAILABLE', interval_id = NULL, occupied_by_ticket = NULL,"
                            + " version = version + 1, updated_at = ?"
                            + " WHERE code = ? AND status = 'REMOVED_PENDING_RETURN' AND occupied_by_ticket = ?"
                            + " AND version = ?",
                    Timestamp.from(now), code, ticketId, req.expectedVersion());
            if (updated == 0) {
                throw ApiException.unprocessable("WIRE_NOT_DETACHED",
                        "接地线 " + code + " 状态已变化，归还拒绝，整批回滚");
            }
            jdbc.update(
                    "UPDATE ground_wire_loan SET status = 'RETURNED', returned_at = ?, return_request_id = ?,"
                            + " keeper_id = ? WHERE id = ?",
                    Timestamp.from(req.occurredAt()), req.requestId(), req.keeperId(), loanId);
        }
        return new WireBatchResult("RETURNED", (int) (req.expectedVersion() + 1), codes, req.occurredAt());
    }

    /**
     * 挂接：引用本票 APPLY_GROUND 步骤，操作人与监护人双人确认，
     * 条件更新成功后状态为已挂接，并只追加一条不可覆盖的操作证据。
     */
    @Transactional
    public WireStepResult attach(long ticketId, AttachWireRequest req) {
        TicketRef ticket = mustLoadTicket(ticketId);
        if (!"ISSUED".equals(ticket.status()) && !"EXECUTING".equals(ticket.status())) {
            throw ApiException.unprocessable("TICKET_NOT_EXECUTING",
                    "仅预发或执行中的操作票可挂接接地线，当前状态: " + ticket.status());
        }
        if (req.operatorId().equals(req.guardianId())) {
            throw ApiException.badRequest("DOUBLE_CONFIRM_REQUIRED", "挂接须操作人与监护人两名不同人员共同确认");
        }
        StepRef step = mustLoadStep(ticketId, req.stepSeq());
        if (!"APPLY_GROUND".equals(step.operationType())) {
            throw ApiException.unprocessable("STEP_TYPE_MISMATCH",
                    "挂接须引用本票挂接地线步骤(APPLY_GROUND)，步序 " + req.stepSeq() + " 类型: " + step.operationType());
        }
        WireRow wire = mustLoadWire(req.wireCode());
        if (wire.version() != req.expectedVersion()) {
            throw ApiException.unprocessable("VERSION_MISMATCH",
                    "接地线 " + wire.code() + " 期望版本 " + req.expectedVersion() + " 与当前版本 " + wire.version() + " 不符");
        }
        if (wire.occupiedByTicket() == null || wire.occupiedByTicket() != ticketId || !"BORROWED".equals(wire.status())) {
            throw ApiException.unprocessable("WIRE_NOT_BORROWED",
                    "接地线 " + wire.code() + " 须由本票借用且处于借出未挂状态，当前状态: " + wire.status());
        }
        // 与送电事务统一加锁顺序：先锁实物行，再复查间隔带电状态，消除“先送电后挂线”的并发交错。
        jdbc.queryForObject("SELECT status FROM ground_wire WHERE code = ? FOR UPDATE", String.class, wire.code());
        long closedBreakers = jdbc.queryForObject(
                "SELECT COUNT(*) FROM device_state WHERE interval_id = ? AND device_type = 'BREAKER' AND state = 'CLOSED'",
                Long.class, ticket.intervalId());
        if (closedBreakers > 0) {
            throw ApiException.unprocessable("LIVE_GROUND",
                    "带电挂接地线：间隔 " + ticket.intervalId() + " 开关仍在合位，实物挂接拒绝");
        }
        Instant now = Instant.now(clock);
        int updated = jdbc.update(
                "UPDATE ground_wire SET status = 'APPLIED', version = version + 1, updated_at = ?"
                        + " WHERE code = ? AND status = 'BORROWED' AND occupied_by_ticket = ? AND version = ?",
                Timestamp.from(now), wire.code(), ticketId, req.expectedVersion());
        if (updated == 0) {
            throw ApiException.unprocessable("WIRE_STATE_CHANGED",
                    "接地线 " + wire.code() + " 状态已变化，挂接拒绝");
        }
        insertEvidence(wire.code(), ticketId, req.stepSeq(), ACTION_APPLIED,
                req.operatorId(), req.guardianId(), req.occurredAt(), req.requestId(), now);
        return new WireStepResult(ACTION_APPLIED, wire.code(), req.stepSeq(), "APPLIED",
                (int) (req.expectedVersion() + 1), req.occurredAt());
    }

    /**
     * 拆除：引用本票 REMOVE_GROUND 步骤，双人确认，只追加不可覆盖证据。
     * 票作废（EXPIRED）或异常（EXCEPTION）不影响合法拆除，仍须双人确认。
     */
    @Transactional
    public WireStepResult detach(long ticketId, DetachWireRequest req) {
        TicketRef ticket = mustLoadTicket(ticketId);
        if (!"ISSUED".equals(ticket.status()) && !"EXECUTING".equals(ticket.status())
                && !"EXPIRED".equals(ticket.status()) && !"EXCEPTION".equals(ticket.status())) {
            throw ApiException.unprocessable("TICKET_STATE",
                    "当前票状态不允许拆除接地线: " + ticket.status());
        }
        if (req.operatorId().equals(req.guardianId())) {
            throw ApiException.badRequest("DOUBLE_CONFIRM_REQUIRED", "拆除须操作人与监护人两名不同人员共同确认");
        }
        StepRef step = findStep(ticketId, req.stepSeq());
        if (step == null) {
            // 继承链实物：本票无该步序时，允许引用继承源票的拆线步序（证据仍登记在本票名下，历史证据不改）
            Long sourceTicket = inheritedSourceTicket(ticketId, req.wireCode());
            if (sourceTicket != null) {
                step = findStep(sourceTicket, req.stepSeq());
            }
        }
        if (step == null) {
            throw ApiException.notFound("STEP_NOT_FOUND", "步骤不存在: " + req.stepSeq());
        }
        if (!"REMOVE_GROUND".equals(step.operationType())) {
            throw ApiException.unprocessable("STEP_TYPE_MISMATCH",
                    "拆除须引用本票拆接地线步骤(REMOVE_GROUND)，步序 " + req.stepSeq() + " 类型: " + step.operationType());
        }
        WireRow wire = mustLoadWire(req.wireCode());
        if (wire.version() != req.expectedVersion()) {
            throw ApiException.unprocessable("VERSION_MISMATCH",
                    "接地线 " + wire.code() + " 期望版本 " + req.expectedVersion() + " 与当前版本 " + wire.version() + " 不符");
        }
        if (wire.occupiedByTicket() == null || wire.occupiedByTicket() != ticketId || !"APPLIED".equals(wire.status())) {
            throw ApiException.unprocessable("WIRE_NOT_APPLIED",
                    "接地线 " + wire.code() + " 须为本票已挂接实物方可拆除，当前状态: " + wire.status());
        }
        Instant now = Instant.now(clock);
        int updated = jdbc.update(
                "UPDATE ground_wire SET status = 'REMOVED_PENDING_RETURN', version = version + 1, updated_at = ?"
                        + " WHERE code = ? AND status = 'APPLIED' AND occupied_by_ticket = ? AND version = ?",
                Timestamp.from(now), wire.code(), ticketId, req.expectedVersion());
        if (updated == 0) {
            throw ApiException.unprocessable("WIRE_STATE_CHANGED",
                    "接地线 " + wire.code() + " 状态已变化，拆除拒绝");
        }
        insertEvidence(wire.code(), ticketId, req.stepSeq(), ACTION_REMOVED,
                req.operatorId(), req.guardianId(), req.occurredAt(), req.requestId(), now);
        return new WireStepResult(ACTION_REMOVED, wire.code(), req.stepSeq(), "REMOVED_PENDING_RETURN",
                (int) (req.expectedVersion() + 1), req.occurredAt());
    }

    /**
     * 送电类步确认在同一事务内调用：先对该间隔全部实物行加行锁，再重新检查是否仍有挂接实物。
     * 行锁与挂接事务的条件更新互斥，保证挂接与送电只能按合法顺序串行，不能既通过送电又留下挂接。
     * 不信任拟票快照，只认台账当前状态。
     */
    @Transactional
    public void assertNoAppliedWireOnEnergize(String intervalId) {
        jdbc.query("SELECT code FROM ground_wire WHERE interval_id = ? FOR UPDATE",
                (rs, n) -> rs.getString(1), intervalId);
        List<String> applied = jdbc.query(
                "SELECT code FROM ground_wire WHERE interval_id = ? AND status = 'APPLIED' AND occupied_by_ticket IS NOT NULL",
                (rs, n) -> rs.getString(1), intervalId);
        if (!applied.isEmpty()) {
            throw ApiException.unprocessable("GROUND_WIRE_STILL_APPLIED",
                    "实物联锁：间隔 " + intervalId + " 接地线 " + applied + " 仍挂接，禁止送电，步序不推进");
        }
    }

    /**
     * 归档前校验（借出方口径）：本票借用的实物必须全部归还，
     * 或已被生效续票继承（原票因继承得以登记中止归档）；历史借用记录保留不删除。
     */
    @Transactional(readOnly = true)
    public void assertAllLoansReturnedOrInherited(long ticketId) {
        List<String> outstanding = jdbc.query(
                "SELECT l.wire_code FROM ground_wire_loan l WHERE l.ticket_id = ? AND l.status = 'BORROWED'"
                        + " AND NOT EXISTS (SELECT 1 FROM ground_wire_custody c"
                        + " JOIN continuation_request r ON r.id = c.continuation_id AND r.status = 'EFFECTIVE'"
                        + " WHERE c.source_ticket_id = l.ticket_id AND c.wire_code = l.wire_code)",
                (rs, n) -> rs.getString(1), ticketId);
        if (!outstanding.isEmpty()) {
            throw ApiException.unprocessable("GROUND_WIRES_NOT_RETURNED",
                    "本票借用接地线 " + outstanding + " 尚未全部归还且未被生效续票继承，不允许归档");
        }
    }

    /**
     * 归档前校验（继承方口径）：本票作为续票继承的实物必须全部核销归还，
     * 不得把继承链责任留给上游票。
     */
    @Transactional(readOnly = true)
    public void assertInheritedLoansReturned(long ticketId) {
        List<String> outstanding = jdbc.query(
                "SELECT c.wire_code FROM ground_wire_custody c"
                        + " JOIN ground_wire_loan l ON l.ticket_id = c.source_ticket_id AND l.wire_code = c.wire_code"
                        + " WHERE c.successor_ticket_id = ? AND l.status = 'BORROWED'",
                (rs, n) -> rs.getString(1), ticketId);
        if (!outstanding.isEmpty()) {
            throw ApiException.unprocessable("INHERITED_WIRES_NOT_RETURNED",
                    "本票继承的接地线 " + outstanding + " 尚未全部归还核销，不允许归档");
        }
    }

    /**
     * 续票签发生效时在同一事务内转移实物责任：原票未归还借用的实物占用权登记到续票名下，
     * 原借用编号与历史证据不改，新增责任接续关系；台账行条件更新失败即整事务回滚。
     *
     * @return 被接续的接地线编号列表（按编号排序）
     */
    @Transactional
    public List<String> transferCustody(long continuationId, long sourceTicketId, long successorTicketId) {
        List<String> wires = jdbc.query(
                "SELECT wire_code FROM ground_wire_loan WHERE ticket_id = ? AND status = 'BORROWED' ORDER BY wire_code",
                (rs, n) -> rs.getString(1), sourceTicketId);
        Instant now = Instant.now(clock);
        for (String code : wires) {
            int updated = jdbc.update(
                    "UPDATE ground_wire SET occupied_by_ticket = ?, version = version + 1, updated_at = ?"
                            + " WHERE code = ? AND occupied_by_ticket = ?",
                    successorTicketId, Timestamp.from(now), code, sourceTicketId);
            if (updated == 0) {
                throw ApiException.conflict("CUSTODY_TRANSFER_FAILED",
                        "接地线 " + code + " 当前占用与原票不一致，责任接续拒绝，整事务回滚");
            }
            jdbc.update(
                    "INSERT INTO ground_wire_custody (continuation_id, source_ticket_id, successor_ticket_id,"
                            + " wire_code, created_at) VALUES (?, ?, ?, ?, ?)",
                    continuationId, sourceTicketId, successorTicketId, code, Timestamp.from(now));
        }
        return wires;
    }

    /** 查询单条实物明细：台账、借用历史与不可覆盖操作证据。 */
    @Transactional(readOnly = true)
    public WireDetailView getWire(String code) {
        WireRow wire = mustLoadWire(code);
        List<LoanView> loans = jdbc.query(
                        "SELECT * FROM ground_wire_loan WHERE wire_code = ? ORDER BY id",
                        LOAN_MAPPER, code).stream()
                .map(GroundWireService::toLoanView)
                .toList();
        List<EvidenceView> evidences = jdbc.query(
                        "SELECT * FROM ground_wire_evidence WHERE wire_code = ? ORDER BY id",
                        EVIDENCE_MAPPER, code).stream()
                .map(GroundWireService::toEvidenceView)
                .toList();
        return new WireDetailView(toWireView(wire), loans, evidences);
    }

    /** 查询全部实物台账，按编号排序。 */
    @Transactional(readOnly = true)
    public List<WireView> listWires() {
        return jdbc.query("SELECT * FROM ground_wire ORDER BY code", WIRE_MAPPER).stream()
                .map(GroundWireService::toWireView)
                .toList();
    }

    /** 查询一张票关联的借用记录（含已归还历史）与全部操作证据。 */
    @Transactional(readOnly = true)
    public com.example.starter.ground.GroundWireDtos.TicketGroundView ticketGround(long ticketId) {
        mustLoadTicket(ticketId);
        List<LoanView> loans = jdbc.query(
                        "SELECT * FROM ground_wire_loan WHERE ticket_id = ? ORDER BY id",
                        LOAN_MAPPER, ticketId).stream()
                .map(GroundWireService::toLoanView)
                .toList();
        List<EvidenceView> evidences = jdbc.query(
                        "SELECT * FROM ground_wire_evidence WHERE ticket_id = ? ORDER BY id",
                        EVIDENCE_MAPPER, ticketId).stream()
                .map(GroundWireService::toEvidenceView)
                .toList();
        return new com.example.starter.ground.GroundWireDtos.TicketGroundView(ticketId, loans, evidences);
    }

    private List<String> normalizedCodes(List<String> codes) {
        List<String> sorted = codes.stream().sorted().toList();
        Set<String> unique = new HashSet<>();
        for (String code : sorted) {
            if (!unique.add(code)) {
                throw ApiException.badRequest("DUPLICATE_WIRE_CODE", "接地线编号在同一请求中重复: " + code);
            }
        }
        return sorted;
    }

    private void insertEvidence(String code, long ticketId, int stepSeq, String action,
                                String operatorId, String guardianId, Instant occurredAt,
                                String requestId, Instant now) {
        jdbc.update(
                "INSERT INTO ground_wire_evidence (wire_code, ticket_id, step_seq, action, operator_id, guardian_id,"
                        + " occurred_at, request_id, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                code, ticketId, stepSeq, action, operatorId, guardianId,
                Timestamp.from(occurredAt), requestId, Timestamp.from(now));
    }

    private TicketRef mustLoadTicket(long ticketId) {
        return jdbc.query("SELECT id, interval_id, status FROM switching_ticket WHERE id = ?",
                        (rs, n) -> new TicketRef(rs.getLong("id"), rs.getString("interval_id"), rs.getString("status")),
                        ticketId)
                .stream().findFirst()
                .orElseThrow(() -> ApiException.notFound("TICKET_NOT_FOUND", "操作票不存在: " + ticketId));
    }

    private StepRef mustLoadStep(long ticketId, int seq) {
        StepRef step = findStep(ticketId, seq);
        if (step == null) {
            throw ApiException.notFound("STEP_NOT_FOUND", "本票步骤不存在: " + seq);
        }
        return step;
    }

    /** 按票与步序查步骤，不存在返回 null。 */
    private StepRef findStep(long ticketId, int seq) {
        return jdbc.query("SELECT seq, operation_type, status FROM switching_step WHERE ticket_id = ? AND seq = ?",
                        (rs, n) -> new StepRef(rs.getInt("seq"), rs.getString("operation_type"), rs.getString("status")),
                        ticketId, seq)
                .stream().findFirst().orElse(null);
    }

    /**
     * 查找本票责任链下的在借记录：本票直接借用，或经责任接续关系接管的原票借用。
     * 责任接续关系在续票签发事务内登记，归还核销与之一致。
     */
    private Long findResponsibleLoan(long ticketId, String wireCode) {
        return jdbc.query(
                        "SELECT l.id FROM ground_wire_loan l WHERE l.wire_code = ? AND l.status = 'BORROWED'"
                                + " AND (l.ticket_id = ? OR EXISTS (SELECT 1 FROM ground_wire_custody c"
                                + " WHERE c.wire_code = l.wire_code AND c.source_ticket_id = l.ticket_id"
                                + " AND c.successor_ticket_id = ?))",
                        (rs, n) -> rs.getLong(1), wireCode, ticketId, ticketId)
                .stream().findFirst().orElse(null);
    }

    /** 本票就该实物是否接管了其他票的责任；是则返回源票主键，否则 null。 */
    private Long inheritedSourceTicket(long ticketId, String wireCode) {
        return jdbc.query(
                        "SELECT source_ticket_id FROM ground_wire_custody"
                                + " WHERE successor_ticket_id = ? AND wire_code = ?",
                        (rs, n) -> rs.getLong(1), ticketId, wireCode)
                .stream().findFirst().orElse(null);
    }

    private WireRow mustLoadWire(String code) {
        return jdbc.query("SELECT * FROM ground_wire WHERE code = ?", WIRE_MAPPER, code)
                .stream().findFirst()
                .orElseThrow(() -> ApiException.notFound("WIRE_NOT_FOUND", "接地线不存在: " + code));
    }

    private static WireView toWireView(WireRow row) {
        return new WireView(row.code(), row.status(), row.intervalId(), row.occupiedByTicket(),
                row.version(), row.updatedAt());
    }

    private static LoanView toLoanView(LoanRow row) {
        return new LoanView(row.id(), row.ticketId(), row.intervalId(), row.wireCode(), row.status(),
                row.borrowedAt(), row.returnedAt(), row.returnRequestId(), row.keeperId());
    }

    private static EvidenceView toEvidenceView(EvidenceRow row) {
        return new EvidenceView(row.id(), row.wireCode(), row.ticketId(), row.stepSeq(), row.action(),
                row.operatorId(), row.guardianId(), row.occurredAt(), row.requestId(), row.createdAt());
    }

    /** 操作票引用。 */
    private record TicketRef(long id, String intervalId, String status) {
    }

    /** 步骤引用。 */
    private record StepRef(int seq, String operationType, String status) {
    }

    /** 实物台账行。 */
    private record WireRow(String code, String status, String intervalId, Long occupiedByTicket,
                          int version, Instant updatedAt) {
    }

    /** 借用历史行。 */
    private record LoanRow(long id, String requestId, long ticketId, String intervalId, String wireCode,
                          String status, Instant borrowedAt, Instant returnedAt,
                          String returnRequestId, String keeperId) {
    }

    /** 操作证据行。 */
    private record EvidenceRow(long id, String wireCode, long ticketId, int stepSeq, String action,
                              String operatorId, String guardianId, Instant occurredAt,
                              String requestId, Instant createdAt) {
    }
}
